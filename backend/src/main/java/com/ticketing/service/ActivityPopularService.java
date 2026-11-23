package com.ticketing.service;

import com.ticketing.entity.Activity;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivitySocialMapper;
import com.ticketing.vo.ActivityLikeCountView;
import com.ticketing.vo.ActivityView;
import com.ticketing.vo.PopularActivityView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ActivityPopularService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActivityPopularService.class);
    private static final String KEY = "ticketing:activity:popular:v2";
    private static final String EMPTY_KEY = KEY + ":empty";
    private static final int MAX_LIMIT = 10;
    // 保留候选，靠前的活动在两次刷新之间结束或下架时由后续名次递补。
    private static final int SNAPSHOT_SIZE = 100;
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            if #ARGV == 0 then
                redis.call('SET', KEYS[2], '1')
            else
                redis.call('ZADD', KEYS[1], unpack(ARGV))
                redis.call('DEL', KEYS[2])
            end
            return 1
            """, Long.class);

    private final ActivitySocialMapper socialMapper;
    private final StringRedisTemplate redis;

    public ActivityPopularService(ActivitySocialMapper socialMapper, StringRedisTemplate redis) {
        this.socialMapper = socialMapper;
        this.redis = redis;
    }

    public List<PopularActivityView> popular(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_POPULAR_LIMIT", "热门活动数量必须为1至10");
        }
        List<ActivityLikeCountView> counts = null;
        try {
            var ranked = redis.opsForZSet().reverseRangeWithScores(KEY, 0, SNAPSHOT_SIZE - 1);
            if (ranked != null && !ranked.isEmpty()) {
                counts = ranked.stream().map(row -> new ActivityLikeCountView(
                        Long.valueOf(row.getValue()), Math.round(row.getScore()))).toList();
            } else if (Boolean.TRUE.equals(redis.hasKey(EMPTY_KEY))) {
                return List.of();
            }
        } catch (DataAccessException exception) {
            LOGGER.warn("热门榜快照暂不可用，改用MySQL查询", exception);
        }
        if (counts == null) counts = socialMapper.selectPopular(limit);
        if (counts.isEmpty()) return List.of();

        var scores = new HashMap<Long, Long>();
        for (var row : counts) scores.put(row.activityId(), row.likeCount());
        var ids = counts.stream().map(ActivityLikeCountView::activityId).toList();
        var result = new ArrayList<PopularActivityView>(limit);
        for (Activity activity : socialMapper.selectRankableActivities(ids, limit)) {
            result.add(new PopularActivityView(result.size() + 1, ActivityView.from(activity), scores.get(activity.getId())));
        }
        return result;
    }

    public void refresh() {
        try {
            var rows = socialMapper.selectPopular(SNAPSHOT_SIZE);
            List<String> arguments = new ArrayList<>(rows.size() * 2);
            for (var row : rows) {
                arguments.add(Long.toString(row.likeCount()));
                arguments.add(row.activityId().toString());
            }
            redis.execute(PUBLISH, List.of(KEY, EMPTY_KEY), arguments.toArray());
        } catch (DataAccessException exception) {
            LOGGER.warn("热门榜刷新失败，等待下一次刷新", exception);
        }
    }
}
