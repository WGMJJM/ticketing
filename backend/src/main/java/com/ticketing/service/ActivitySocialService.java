package com.ticketing.service;

import com.ticketing.common.PageResponse;
import com.ticketing.entity.Activity;
import com.ticketing.entity.ActivitySession;
import com.ticketing.enums.ActivityStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.ActivitySocialMapper;
import com.ticketing.vo.ActivityEngagementView;
import com.ticketing.vo.ActivityView;
import com.ticketing.vo.FollowedActivityView;
import com.ticketing.vo.SessionView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ActivitySocialService {
    private final ActivityService activities;
    private final ActivityMapper activityMapper;
    private final ActivitySessionMapper sessionMapper;
    private final ActivitySocialMapper socialMapper;
    private final ActivityPopularService popularActivities;

    public ActivitySocialService(ActivityService activities, ActivityMapper activityMapper,
            ActivitySessionMapper sessionMapper, ActivitySocialMapper socialMapper,
            ActivityPopularService popularActivities) {
        this.activities = activities;
        this.activityMapper = activityMapper;
        this.sessionMapper = sessionMapper;
        this.socialMapper = socialMapper;
        this.popularActivities = popularActivities;
    }

    public ActivityEngagementView engagement(long activityId, Long userId) {
        activities.requireActivity(activityId, true);
        return socialMapper.engagement(activityId, userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActivityEngagementView like(long userId, long activityId) {
        requirePublished(activities.lockActivityForShare(activityId));
        if (socialMapper.insertLike(userId, activityId) == 1) adjustPopularAfterCommit(activityId, 1);
        return socialMapper.engagement(activityId, userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActivityEngagementView unlike(long userId, long activityId) {
        activities.lockActivityForShare(activityId);
        if (socialMapper.deleteLike(userId, activityId) == 1) adjustPopularAfterCommit(activityId, -1);
        return socialMapper.engagement(activityId, userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActivityEngagementView follow(long userId, long activityId) {
        requirePublished(activities.lockActivityForShare(activityId));
        socialMapper.insertFollow(userId, activityId);
        return socialMapper.engagement(activityId, userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActivityEngagementView unfollow(long userId, long activityId) {
        activities.lockActivityForShare(activityId);
        socialMapper.deleteFollow(userId, activityId);
        return socialMapper.engagement(activityId, userId);
    }

    public PageResponse<FollowedActivityView> followed(long userId, int page, int size) {
        long total = socialMapper.countFollowed(userId);
        long offset = (long) (page - 1) * size;
        List<Long> ids = socialMapper.selectFollowedIds(userId, size, offset);
        if (ids.isEmpty()) return new PageResponse<>(List.of(), total, page, size);

        Map<Long, Activity> activityById = new HashMap<>();
        activityMapper.selectBatchIds(ids).forEach(activity -> activityById.put(activity.getId(), activity));
        Map<Long, List<SessionView>> sessionsByActivity = new HashMap<>();
        for (ActivitySession session : sessionMapper.selectByActivityIds(ids)) {
            sessionsByActivity.computeIfAbsent(session.getActivityId(), ignored -> new ArrayList<>())
                    .add(SessionView.from(session));
        }

        List<FollowedActivityView> items = new ArrayList<>();
        for (Long id : ids) {
            Activity activity = activityById.get(id);
            if (activity == null) continue;
            List<SessionView> sessions = ActivityStatus.PUBLISHED.name().equals(activity.getStatus())
                    ? sessionsByActivity.getOrDefault(id, List.of()) : List.of();
            items.add(new FollowedActivityView(ActivityView.from(activity), sessions));
        }
        return new PageResponse<>(items, total, page, size);
    }

    private void requirePublished(Activity activity) {
        if (!ActivityStatus.PUBLISHED.name().equals(activity.getStatus())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "活动不存在或暂未开放");
        }
    }

    private void adjustPopularAfterCommit(long activityId, int delta) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("点赞排行榜更新必须关联数据库事务");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                popularActivities.adjust(activityId, delta);
            }
        });
    }
}
