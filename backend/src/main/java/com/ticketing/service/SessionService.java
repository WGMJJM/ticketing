package com.ticketing.service;

import com.ticketing.cache.CatalogCache;
import com.ticketing.dto.SessionWriteRequest;
import com.ticketing.entity.Activity;
import com.ticketing.entity.ActivitySession;
import com.ticketing.enums.AuditAction;
import com.ticketing.enums.SessionStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.messaging.InventoryEvents;
import com.ticketing.vo.SessionView;

import com.fasterxml.jackson.core.type.TypeReference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class SessionService {
    private static final int MAX_SESSIONS_PER_ACTIVITY = 200;
    private final ActivitySessionMapper mapper;
    private final ActivityService activityService;
    private final List<SessionWindowGuard> windowGuards;
    private final ActivityMapper activities;
    private final AuditWriter audit;
    private final CatalogCache cache;
    private final InventoryEvents inventoryEvents;

    public SessionService(
            ActivitySessionMapper mapper,
            ActivityService activityService,
            List<SessionWindowGuard> windowGuards,
            ActivityMapper activities, AuditWriter audit, CatalogCache cache, InventoryEvents inventoryEvents) {
        this.mapper = mapper;
        this.activityService = activityService;
        this.windowGuards = List.copyOf(windowGuards);
        this.activities = activities;
        this.audit = audit;
        this.cache = cache;
        this.inventoryEvents = inventoryEvents;
    }

    public List<SessionView> list(long activityId, boolean publicOnly) {
        if (publicOnly) return cache.read(CatalogCache.Kind.SESSIONS, Long.toString(activityId), new TypeReference<List<SessionView>>() {},
                () -> queryList(activityId, true));
        return queryList(activityId, false);
    }

    private List<SessionView> queryList(long activityId, boolean publicOnly) {
        activityService.requireActivity(activityId, publicOnly);
        return mapper
                .selectList(
                        new LambdaQueryWrapper<ActivitySession>()
                                .eq(ActivitySession::getActivityId, activityId)
                                .orderByAsc(ActivitySession::getStartsAt)
                                .orderByAsc(ActivitySession::getId)
                                .last("LIMIT " + MAX_SESSIONS_PER_ACTIVITY))
                .stream()
                .map(SessionView::from)
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SessionView create(long activityId, SessionWriteRequest request) {
        long operatorId = audit.begin();
        activityService.lockActivity(activityId);
        if (mapper.selectCount(
                        new LambdaQueryWrapper<ActivitySession>()
                                .eq(ActivitySession::getActivityId, activityId))
                >= MAX_SESSIONS_PER_ACTIVITY) {
            throw conflict("每个活动最多支持200个场次");
        }
        ActivitySession session = new ActivitySession();
        session.setActivityId(activityId);
        session.setTitle(request.title().trim());
        session.setStartsAt(utc(request.startsAt()));
        session.setEndsAt(utc(request.endsAt()));
        session.setSaleStartsAt(utc(request.saleStartsAt()));
        session.setStatus(SessionStatus.SCHEDULED.name());
        session.setVersion(0);
        session.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        session.setUpdatedAt(session.getCreatedAt());
        mapper.insert(session);
        audit.record(operatorId, AuditAction.SESSION_CREATE, "SESSION", session.getId(), Map.of(), snapshot(session));
        cache.invalidateAfterCommit();
        return SessionView.from(session);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SessionView update(long id, SessionWriteRequest request) {
        long operatorId = audit.begin();
        ActivitySession session = lockSession(id);
        if (!SessionStatus.SCHEDULED.name().equals(session.getStatus())) {
            throw conflict("已取消或结束的场次不能修改");
        }
        if (request.version() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "缺少数据版本，请刷新页面");
        }
        for (SessionWindowGuard guard : windowGuards) {
            guard.validate(id, utc(request.saleStartsAt()), utc(request.startsAt()));
        }
        var wrapper =
                mutation(session, request.version())
                        .set(ActivitySession::getTitle, request.title().trim())
                        .set(ActivitySession::getStartsAt, utc(request.startsAt()))
                        .set(ActivitySession::getEndsAt, utc(request.endsAt()))
                        .set(ActivitySession::getSaleStartsAt, utc(request.saleStartsAt()));
        if (mapper.update(null, wrapper) != 1) {
            throw conflict("场次已被修改，请刷新后重试");
        }
        audit.record(operatorId, AuditAction.SESSION_UPDATE, "SESSION", id, snapshot(session), snapshot(requireSession(id)));
        inventoryEvents.sessionConfigurationChanged(id);
        cache.invalidateAfterCommit();
        return SessionView.from(requireSession(id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SessionView cancel(long id, int version) {
        long operatorId = audit.begin();
        ActivitySession session = lockSession(id);
        if (!SessionStatus.SCHEDULED.name().equals(session.getStatus())) {
            throw conflict("只有正常场次可以取消");
        }
        if (mapper.update(
                        null,
                        mutation(session, version)
                                .set(ActivitySession::getStatus, SessionStatus.CANCELLED.name()))
                != 1) {
            throw conflict("场次状态已变化，请刷新后重试");
        }
        audit.record(operatorId, AuditAction.SESSION_CANCEL, "SESSION", id, snapshot(session), snapshot(requireSession(id)));
        inventoryEvents.sessionConfigurationChanged(id);
        cache.invalidateAfterCommit();
        return SessionView.from(requireSession(id));
    }

    private Map<String, Object> snapshot(ActivitySession session) {
        return Map.of("status", session.getStatus(), "title", session.getTitle(), "version", session.getVersion(),
                "startsAt", session.getStartsAt().atOffset(ZoneOffset.UTC), "endsAt", session.getEndsAt().atOffset(ZoneOffset.UTC),
                "saleStartsAt", session.getSaleStartsAt().atOffset(ZoneOffset.UTC));
    }

    private ActivitySession requireSession(long id) {
        ActivitySession session = mapper.selectById(id);
        if (session == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "场次不存在");
        }
        return session;
    }

    private ActivitySession lockSession(long id) {
        ActivitySession parent = requireSession(id);
        activities.selectOne(
                new LambdaQueryWrapper<Activity>()
                        .eq(Activity::getId, parent.getActivityId())
                        .last("FOR SHARE"));
        ActivitySession session =
                mapper.selectOne(
                        new LambdaQueryWrapper<ActivitySession>()
                                .eq(ActivitySession::getId, id)
                                .last("FOR UPDATE"));
        if (session == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "场次不存在");
        }
        return session;
    }

    private LambdaUpdateWrapper<ActivitySession> mutation(ActivitySession session, int version) {
        return new LambdaUpdateWrapper<ActivitySession>()
                .eq(ActivitySession::getId, session.getId())
                .eq(ActivitySession::getVersion, version)
                .eq(ActivitySession::getStatus, SessionStatus.SCHEDULED.name())
                .set(ActivitySession::getVersion, version + 1)
                .set(ActivitySession::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC));
    }

    private LocalDateTime utc(java.time.OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "SESSION_CONFLICT", message);
    }
}
