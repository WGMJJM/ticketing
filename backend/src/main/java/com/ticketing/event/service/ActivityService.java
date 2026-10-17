package com.ticketing.event.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ticketing.common.api.BusinessException;
import com.ticketing.common.api.PageResponse;
import com.ticketing.event.dto.ActivityQuery;
import com.ticketing.event.dto.ActivityView;
import com.ticketing.event.dto.ActivityWriteRequest;
import com.ticketing.event.entity.Activity;
import com.ticketing.event.mapper.ActivityMapper;
import com.ticketing.event.model.ActivityStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ActivityService {
    private final ActivityMapper mapper;

    public ActivityService(ActivityMapper mapper) { this.mapper = mapper; }

    public PageResponse<ActivityView> list(ActivityQuery query, boolean publicOnly) {
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

    @Transactional
    public ActivityView create(ActivityWriteRequest request) {
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
        return ActivityView.from(activity);
    }

    @Transactional
    public ActivityView update(long id, ActivityWriteRequest request) {
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
        return detail(id, false);
    }

    @Transactional
    public ActivityView publish(long id, int version) {
        Activity activity = requireActivity(id, false);
        if (ActivityStatus.PUBLISHED.name().equals(activity.getStatus())) throw conflict("活动已经发布");
        return transition(activity, ActivityStatus.PUBLISHED, version);
    }

    @Transactional
    public ActivityView offline(long id, int version) {
        Activity activity = requireActivity(id, false);
        if (!ActivityStatus.PUBLISHED.name().equals(activity.getStatus())) throw conflict("只有已发布的活动可以下架");
        return transition(activity, ActivityStatus.OFFLINE, version);
    }

    private ActivityView transition(Activity activity, ActivityStatus next, int version) {
        requireVersion(activity.getVersion(), version);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var wrapper = new LambdaUpdateWrapper<Activity>()
                .eq(Activity::getId, activity.getId()).eq(Activity::getStatus, activity.getStatus())
                .eq(Activity::getVersion, version).set(Activity::getStatus, next.name())
                .set(Activity::getVersion, version + 1).set(Activity::getUpdatedAt, now);
        if (next == ActivityStatus.PUBLISHED) wrapper.set(Activity::getPublishedAt, now);
        if (mapper.update(null, wrapper) != 1) throw conflict("活动状态已变化，请刷新后重试");
        return detail(activity.getId(), false);
    }

    private String normalizeCover(String coverUrl) { return StringUtils.hasText(coverUrl) ? coverUrl.trim() : null; }

    private void requireVersion(int actual, Integer supplied) {
        if (supplied == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "缺少数据版本，请刷新页面");
        if (actual != supplied) throw conflict("数据已被修改，请刷新后重试");
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "ACTIVITY_CONFLICT", message);
    }
}
