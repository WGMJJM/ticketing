package com.ticketing.service;

import com.ticketing.cache.CatalogCache;
import com.ticketing.common.PageResponse;
import com.ticketing.dto.ActivityQuery;
import com.ticketing.dto.ActivityWriteRequest;
import com.ticketing.entity.Activity;
import com.ticketing.enums.ActivityStatus;
import com.ticketing.enums.AuditAction;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.messaging.InventoryEvents;
import com.ticketing.vo.ActivityView;

import com.fasterxml.jackson.core.type.TypeReference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.StringUtils;

@Service
public class ActivityService {
    private final ActivityMapper mapper;
    private final AuditWriter audit;
    private final CatalogCache cache;
    private final InventoryEvents inventoryEvents;

    public ActivityService(ActivityMapper mapper, AuditWriter audit, CatalogCache cache, InventoryEvents inventoryEvents) {
        this.mapper = mapper; this.audit = audit; this.cache = cache; this.inventoryEvents = inventoryEvents;
    }

    public PageResponse<ActivityView> list(ActivityQuery query, boolean publicOnly) {
        if (publicOnly && cache.cacheList(query)) {
            String city = query.city() == null ? "" : query.city().trim();
            String key = (query.category() == null ? "ALL" : query.category().name()) + ":" + city + ":" + query.page() + ":" + query.size();
            return cache.read(CatalogCache.Kind.LIST, key, new TypeReference<PageResponse<ActivityView>>() {}, () -> queryList(query, true));
        }
        return queryList(query, publicOnly);
    }

    private PageResponse<ActivityView> queryList(ActivityQuery query, boolean publicOnly) {
        var wrapper = new LambdaQueryWrapper<Activity>();
        if (publicOnly) wrapper.eq(Activity::getStatus, ActivityStatus.PUBLISHED.name());
        else if (query.status() != null) wrapper.eq(Activity::getStatus, query.status().name());
        if (StringUtils.hasText(query.keyword())) {
            String keyword = query.keyword().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            wrapper.like(Activity::getTitle, keyword);
        }
        if (query.category() != null) wrapper.eq(Activity::getCategory, query.category().name());
        if (StringUtils.hasText(query.city())) wrapper.eq(Activity::getCity, query.city().trim());
        long total = mapper.selectCount(wrapper);
        if (publicOnly) wrapper.orderByDesc(Activity::getPublishedAt).orderByDesc(Activity::getId);
        else wrapper.orderByDesc(Activity::getCreatedAt).orderByDesc(Activity::getId);
        // 页码和数量由 Jakarta Validation 限制；SQL 尾部仅拼接整数。
        long offset = (long) (query.page() - 1) * query.size();
        wrapper.last("LIMIT " + query.size() + " OFFSET " + offset);
        var items = mapper.selectList(wrapper).stream().map(ActivityView::from).toList();
        return new PageResponse<>(items, total, query.page(), query.size());
    }

    public ActivityView detail(long id, boolean publicOnly) {
        if (publicOnly) return cache.read(CatalogCache.Kind.DETAIL, Long.toString(id), new TypeReference<ActivityView>() {},
                () -> ActivityView.from(requireActivity(id, true)));
        return ActivityView.from(requireActivity(id, publicOnly));
    }

    public Activity requireActivity(long id, boolean publicOnly) {
        Activity activity = mapper.selectById(id);
        if (activity == null || (publicOnly && !ActivityStatus.PUBLISHED.name().equals(activity.getStatus()))) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "活动不存在或暂未开放");
        }
        return activity;
    }

    public void lockActivity(long id) {
        Activity activity = mapper.selectOne(new LambdaQueryWrapper<Activity>().eq(Activity::getId, id).last("FOR UPDATE"));
        if (activity == null) throw new BusinessException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "活动不存在");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActivityView create(ActivityWriteRequest request) {
        long operatorId = audit.begin();
        Activity activity = new Activity();
        activity.setTitle(request.title().trim());
        activity.setCategory(request.category().name());
        activity.setCity(request.city().trim());
        activity.setVenue(request.venue().trim());
        activity.setSummary(request.summary().trim());
        activity.setDescription(request.description().trim());
        activity.setCoverUrl(normalizeCover(request.coverUrl()));
        activity.setStatus(ActivityStatus.DRAFT.name());
        activity.setVersion(0);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        activity.setCreatedAt(now);
        activity.setUpdatedAt(now);
        mapper.insert(activity);
        audit.record(operatorId, AuditAction.ACTIVITY_CREATE, "ACTIVITY", activity.getId(), Map.of(), snapshot(activity));
        cache.invalidateAfterCommit();
        return ActivityView.from(activity);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActivityView update(long id, ActivityWriteRequest request) {
        long operatorId = audit.begin();
        lockActivity(id);
        Activity activity = requireActivity(id, false);
        if (ActivityStatus.PUBLISHED.name().equals(activity.getStatus())) throw conflict("请先下架活动，再修改活动内容");
        requireVersion(activity.getVersion(), request.version());
        var wrapper = new LambdaUpdateWrapper<Activity>()
                .eq(Activity::getId, id).eq(Activity::getVersion, request.version()).eq(Activity::getStatus, activity.getStatus())
                .set(Activity::getTitle, request.title().trim()).set(Activity::getCategory, request.category().name())
                .set(Activity::getCity, request.city().trim()).set(Activity::getVenue, request.venue().trim())
                .set(Activity::getSummary, request.summary().trim()).set(Activity::getDescription, request.description().trim())
                .set(Activity::getCoverUrl, normalizeCover(request.coverUrl()))
                .set(Activity::getVersion, activity.getVersion() + 1).set(Activity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC));
        if (mapper.update(null, wrapper) != 1) throw conflict("活动已被其他操作修改，请刷新后重试");
        audit.record(operatorId, AuditAction.ACTIVITY_UPDATE, "ACTIVITY", id, snapshot(activity), snapshot(mapper.selectById(id)));
        cache.invalidateAfterCommit();
        return detail(id, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActivityView publish(long id, int version) {
        long operatorId = audit.begin();
        lockActivity(id);
        Activity activity = requireActivity(id, false);
        if (ActivityStatus.PUBLISHED.name().equals(activity.getStatus())) throw conflict("活动已经发布");
        return transition(operatorId, activity, ActivityStatus.PUBLISHED, version);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActivityView offline(long id, int version) {
        long operatorId = audit.begin();
        lockActivity(id);
        Activity activity = requireActivity(id, false);
        if (!ActivityStatus.PUBLISHED.name().equals(activity.getStatus())) throw conflict("只有已发布的活动可以下架");
        return transition(operatorId, activity, ActivityStatus.OFFLINE, version);
    }

    private ActivityView transition(long operatorId, Activity activity, ActivityStatus next, int version) {
        requireVersion(activity.getVersion(), version);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var wrapper = new LambdaUpdateWrapper<Activity>()
                .eq(Activity::getId, activity.getId()).eq(Activity::getStatus, activity.getStatus())
                .eq(Activity::getVersion, version).set(Activity::getStatus, next.name())
                .set(Activity::getVersion, version + 1).set(Activity::getUpdatedAt, now);
        if (next == ActivityStatus.PUBLISHED) wrapper.set(Activity::getPublishedAt, now);
        if (mapper.update(null, wrapper) != 1) throw conflict("活动状态已变化，请刷新后重试");
        inventoryEvents.activityConfigurationChanged(activity.getId());
        audit.record(operatorId, next == ActivityStatus.PUBLISHED ? AuditAction.ACTIVITY_PUBLISH : AuditAction.ACTIVITY_OFFLINE,
                "ACTIVITY", activity.getId(), snapshot(activity), snapshot(mapper.selectById(activity.getId())));
        cache.invalidateAfterCommit();
        return detail(activity.getId(), false);
    }

    private String normalizeCover(String coverUrl) { return StringUtils.hasText(coverUrl) ? coverUrl.trim() : null; }

    private Map<String, Object> snapshot(Activity activity) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", activity.getStatus());
        fields.put("title", activity.getTitle());
        fields.put("category", activity.getCategory());
        fields.put("city", activity.getCity());
        fields.put("venue", activity.getVenue());
        fields.put("version", activity.getVersion());
        fields.put("summary", activity.getSummary());
        fields.put("description", activity.getDescription());
        fields.put("coverUrl", activity.getCoverUrl());
        return fields;
    }

    private void requireVersion(int actual, Integer supplied) {
        if (supplied == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "缺少数据版本，请刷新页面");
        if (actual != supplied) throw conflict("数据已被修改，请刷新后重试");
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "ACTIVITY_CONFLICT", message);
    }
}
