package com.ticketing.service;

import com.ticketing.entity.Activity;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.ActivitySocialMapper;
import com.ticketing.vo.ActivityEngagementView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivitySocialServiceTest {
    @Mock private ActivityService activities;
    @Mock private ActivityMapper activityMapper;
    @Mock private ActivitySessionMapper sessionMapper;
    @Mock private ActivitySocialMapper socialMapper;

    @Test
    void likeAndUnlikeReturnCurrentDatabaseCounts() {
        when(activities.lockActivityForShare(9L)).thenReturn(publishedActivity());
        when(socialMapper.engagement(9L, 4L))
                .thenReturn(new ActivityEngagementView(3, 1, true, false),
                        new ActivityEngagementView(2, 1, false, false));

        assertThat(service().like(4L, 9L).likeCount()).isEqualTo(3);
        assertThat(service().unlike(4L, 9L).likeCount()).isEqualTo(2);
        verify(socialMapper).insertLike(4L, 9L);
        verify(socialMapper).deleteLike(4L, 9L);
    }

    @Test
    void repeatedFollowAndUnfollowReturnDatabaseStateWithoutChangingItTwice() {
        Activity activity = publishedActivity();
        when(activities.lockActivityForShare(9L)).thenReturn(activity);
        when(socialMapper.engagement(9L, 4L))
                .thenReturn(new ActivityEngagementView(2, 1, true, true),
                        new ActivityEngagementView(2, 1, true, false));

        ActivitySocialService service = service();

        assertThat(service.follow(4L, 9L).following()).isTrue();
        assertThat(service.unfollow(4L, 9L).following()).isFalse();
        verify(socialMapper).insertFollow(4L, 9L);
        verify(socialMapper).deleteFollow(4L, 9L);
    }

    private ActivitySocialService service() {
        return new ActivitySocialService(activities, activityMapper, sessionMapper, socialMapper);
    }

    private Activity publishedActivity() {
        Activity activity = new Activity();
        activity.setId(9L);
        activity.setStatus("PUBLISHED");
        return activity;
    }
}
