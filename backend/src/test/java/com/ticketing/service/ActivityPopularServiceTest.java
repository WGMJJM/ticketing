package com.ticketing.service;

import com.ticketing.entity.Activity;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySocialMapper;
import com.ticketing.vo.ActivityLikeCountView;
import com.ticketing.vo.PopularActivityView;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityPopularServiceTest {
    @Mock private ActivitySocialMapper socialMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private ZSetOperations<String, String> sortedSet;

    @Test
    void redisRankingUsesDescendingScoresAndKeepsItsOrderAfterBatchActivityLookup() {
        var high = tuple("20", 9.0);
        var low = tuple("10", 4.0);
        Set<ZSetOperations.TypedTuple<String>> ranking = new LinkedHashSet<>(List.of(high, low));
        when(socialMapper.countPublishedActivities()).thenReturn(2L);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("ticketing:activity:popular:v1:published-count")).thenReturn("2");
        when(redis.opsForZSet()).thenReturn(sortedSet);
        when(sortedSet.zCard("ticketing:activity:popular:v1")).thenReturn(2L);
        when(sortedSet.reverseRangeWithScores("ticketing:activity:popular:v1", 0, 99)).thenReturn(ranking);
        when(activityMapper.selectBatchIds(List.of(20L, 10L))).thenReturn(List.of(activity(10), activity(20)));

        List<PopularActivityView> result = service().popular(2);

        assertThat(result).extracting(item -> item.activity().id()).containsExactly("20", "10");
        assertThat(result).extracting(PopularActivityView::likeCount).containsExactly(9L, 4L);
        verify(sortedSet, never()).rangeWithScores("ticketing:activity:popular:v1", 0, 99);
    }

    @Test
    void redisFailureFallsBackToBoundedMySqlRanking() {
        when(socialMapper.countPublishedActivities()).thenReturn(2L);
        when(redis.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
        when(socialMapper.selectPopular(2)).thenReturn(List.of(
                new ActivityLikeCountView(20L, 9), new ActivityLikeCountView(10L, 4)));
        when(activityMapper.selectBatchIds(List.of(20L, 10L))).thenReturn(List.of(activity(10), activity(20)));

        List<PopularActivityView> result = service().popular(2);

        assertThat(result).extracting(item -> item.activity().id()).containsExactly("20", "10");
        assertThat(result).extracting(PopularActivityView::likeCount).containsExactly(9L, 4L);
    }

    @SuppressWarnings("unchecked")
    private ZSetOperations.TypedTuple<String> tuple(String value, double score) {
        ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
        when(tuple.getValue()).thenReturn(value);
        when(tuple.getScore()).thenReturn(score);
        return tuple;
    }

    private ActivityPopularService service() {
        return new ActivityPopularService(socialMapper, activityMapper, redis);
    }

    private Activity activity(long id) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setTitle("演出" + id);
        activity.setCategory("CONCERT");
        activity.setCity("上海");
        activity.setVenue("现场");
        activity.setSummary("摘要");
        activity.setDescription("说明");
        activity.setStatus("PUBLISHED");
        activity.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        activity.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        activity.setVersion(1);
        return activity;
    }
}
