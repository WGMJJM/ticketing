package com.ticketing.service;

import com.ticketing.entity.Activity;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.ActivitySocialMapper;
import com.ticketing.vo.ActivityEngagementView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivitySocialServiceTest {
    @Mock private ActivityService activities;
    @Mock private ActivityMapper activityMapper;
    @Mock private ActivitySessionMapper sessionMapper;
    @Mock private ActivitySocialMapper socialMapper;
    @Mock private ActivityPopularService popularActivities;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void duplicateLikeDoesNotChangePopularScore() {
        Activity activity = publishedActivity();
        when(activities.lockActivityForShare(9L)).thenReturn(activity);
        when(socialMapper.insertLike(4L, 9L)).thenReturn(0);
        when(socialMapper.engagement(9L, 4L)).thenReturn(new ActivityEngagementView(2, 1, true, true));

        ActivitySocialService service = service();
        service.like(4L, 9L);

        verify(popularActivities, never()).adjust(9L, 1);
    }

    @Test
    void newLikeAdjustsPopularScoreOnlyAfterCommit() {
        Activity activity = publishedActivity();
        when(activities.lockActivityForShare(9L)).thenReturn(activity);
        when(socialMapper.insertLike(4L, 9L)).thenReturn(1);
        when(socialMapper.engagement(9L, 4L)).thenReturn(new ActivityEngagementView(3, 1, true, true));
        TransactionSynchronizationManager.initSynchronization();

        service().like(4L, 9L);

        verify(popularActivities, never()).adjust(9L, 1);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(popularActivities).adjust(9L, 1);
    }

    @Test
    void repeatedFollowAndUnfollowReturnDatabaseStateWithoutChangingItTwice() {
        Activity activity = publishedActivity();
        when(activities.lockActivityForShare(9L)).thenReturn(activity);
        when(socialMapper.insertFollow(4L, 9L)).thenReturn(0);
        when(socialMapper.deleteFollow(4L, 9L)).thenReturn(0);
        when(socialMapper.engagement(9L, 4L))
                .thenReturn(new ActivityEngagementView(2, 1, true, true),
                        new ActivityEngagementView(2, 1, true, false));

        ActivitySocialService service = service();

        assertThat(service.follow(4L, 9L).following()).isTrue();
        assertThat(service.unfollow(4L, 9L).following()).isFalse();
        verify(socialMapper).insertFollow(4L, 9L);
        verify(socialMapper).deleteFollow(4L, 9L);
        verify(popularActivities, never()).adjust(9L, 1);
        verify(popularActivities, never()).adjust(9L, -1);
    }

    private ActivitySocialService service() {
        return new ActivitySocialService(activities, activityMapper, sessionMapper, socialMapper, popularActivities);
    }

    private Activity publishedActivity() {
        Activity activity = new Activity();
        activity.setId(9L);
        activity.setStatus("PUBLISHED");
        return activity;
    }
}
