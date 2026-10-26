package com.ticketing.service;

import com.ticketing.entity.Activity;
import com.ticketing.enums.ActivityStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySocialMapper;
import com.ticketing.vo.ActivityLikeCountView;
import com.ticketing.vo.ActivityView;
import com.ticketing.vo.PopularActivityView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class ActivityPopularService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActivityPopularService.class);
    private static final String KEY = "ticketing:activity:popular:v1";
    private static final String READY_KEY = "ticketing:activity:popular:v1:published-count";
    private static final int PAGE_SIZE = 500;
    private static final int READ_BATCH_SIZE = 100;
    private static final int MAX_CANDIDATES = 500;
    private static final Duration TEMP_KEY_TTL = Duration.ofMinutes(15);
    private static final DefaultRedisScript<Long> ADD_BATCH = new DefaultRedisScript<>("""
            for i = 1, #ARGV, 2 do redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i + 1]) end
            redis.call('PEXPIRE', KEYS[1], 900000)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ADJUST_SCORE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) == false then return 0 end
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not score then return 0 end
            local nextScore = tonumber(score) + tonumber(ARGV[2])
            if nextScore < 0 then nextScore = 0 end
            redis.call('ZADD', KEYS[1], nextScore, ARGV[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> PUBLISH_REBUILD = new DefaultRedisScript<>("""
            if tonumber(ARGV[1]) > 0 then
              redis.call('RENAME', KEYS[1], KEYS[2])
              redis.call('PERSIST', KEYS[2])
            else redis.call('DEL', KEYS[2]) end
            redis.call('SET', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

    private final ActivitySocialMapper socialMapper;
    private final ActivityMapper activityMapper;
    private final StringRedisTemplate redis;
    private final ReentrantLock rebuildLock = new ReentrantLock();

    public ActivityPopularService(ActivitySocialMapper socialMapper, ActivityMapper activityMapper,
            StringRedisTemplate redis) {
        this.socialMapper = socialMapper;
        this.activityMapper = activityMapper;
        this.redis = redis;
    }

    public List<PopularActivityView> popular(int limit) {
        if (limit < 1 || limit > 10) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_POPULAR_LIMIT", "热门活动数量必须为1至10");
        }
        try {
            long publishedCount = socialMapper.countPublishedActivities();
            String indexedCount = redis.opsForValue().get(READY_KEY);
            Long indexedMembers = redis.opsForZSet().zCard(KEY);
            long memberCount = indexedMembers == null ? 0 : indexedMembers;
            if (!Long.toString(publishedCount).equals(indexedCount)
                    || memberCount != publishedCount) {
                if (!rebuild()) return fromDatabase(limit);
            }
            List<PopularActivityView> ranked = fromRedis(limit);
            return ranked.size() == limit || publishedCount <= ranked.size()
                    ? ranked : fromDatabase(limit);
        } catch (DataAccessException exception) {
            LOGGER.warn("热门活动排行榜暂不可用，改用MySQL查询", exception);
            return fromDatabase(limit);
        }
    }

    /** 由点赞事务提交回调调用；Redis失败不得影响已提交的点赞事实。 */
    public void adjust(long activityId, int delta) {
        try {
            redis.execute(ADJUST_SCORE, List.of(KEY, READY_KEY), Long.toString(activityId), Integer.toString(delta));
        } catch (RuntimeException exception) {
            LOGGER.warn("活动点赞已保存，但热门榜分数未能即时更新，等待校准任务恢复。活动ID={}", activityId, exception);
        }
    }

    public void refresh() {
        try {
            rebuild();
        } catch (RuntimeException exception) {
            LOGGER.warn("热门活动排行榜重建失败，保留现有可读榜单", exception);
        }
    }

    private boolean rebuild() {
        if (!rebuildLock.tryLock()) return false;
        String temporaryKey = KEY + ":rebuild:" + UUID.randomUUID();
        try {
            redis.delete(temporaryKey);
            long maxId = socialMapper.maxPublishedId();
            long cursor = 0;
            long memberCount = 0;
            while (cursor < maxId) {
                List<ActivityLikeCountView> rows = socialMapper.selectPublishedCountsAfter(cursor, maxId, PAGE_SIZE);
                if (rows.isEmpty()) break;
                String[] arguments = new String[rows.size() * 2];
                int position = 0;
                for (ActivityLikeCountView row : rows) {
                    arguments[position++] = Long.toString(row.likeCount());
                    arguments[position++] = row.activityId().toString();
                }
                redis.execute(ADD_BATCH, List.of(temporaryKey), (Object[]) arguments);
                cursor = rows.get(rows.size() - 1).activityId();
                memberCount += rows.size();
            }
            if (memberCount > 0) redis.expire(temporaryKey, TEMP_KEY_TTL);
            redis.execute(PUBLISH_REBUILD, List.of(temporaryKey, KEY, READY_KEY), Long.toString(memberCount));
            return true;
        } catch (RuntimeException exception) {
            try { redis.delete(temporaryKey); }
            catch (RuntimeException ignored) { /* 临时键有过期时间 */ }
            throw exception;
        } finally {
            rebuildLock.unlock();
        }
    }

    private List<PopularActivityView> fromRedis(int limit) {
        List<PopularActivityView> result = new ArrayList<>(limit);
        long offset = 0;
        while (result.size() < limit && offset < MAX_CANDIDATES) {
            long end = Math.min(offset + READ_BATCH_SIZE - 1, MAX_CANDIDATES - 1);
            Set<TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(KEY, offset, end);
            if (tuples == null || tuples.isEmpty()) break;
            List<Long> ids = tuples.stream().map(TypedTuple::getValue).map(Long::valueOf).toList();
            Map<Long, Activity> publishedById = new HashMap<>();
            activityMapper.selectBatchIds(ids).stream()
                    .filter(activity -> ActivityStatus.PUBLISHED.name().equals(activity.getStatus()))
                    .forEach(activity -> publishedById.put(activity.getId(), activity));
            for (TypedTuple<String> tuple : tuples) {
                Activity activity = publishedById.get(Long.valueOf(tuple.getValue()));
                if (activity != null) {
                    result.add(new PopularActivityView(result.size() + 1, ActivityView.from(activity),
                            Math.round(tuple.getScore())));
                    if (result.size() == limit) break;
                }
            }
            offset += tuples.size();
        }
        return result;
    }

    private List<PopularActivityView> fromDatabase(int limit) {
        List<ActivityLikeCountView> counts = socialMapper.selectPopular(limit);
        if (counts.isEmpty()) return List.of();
        List<Long> ids = counts.stream().map(ActivityLikeCountView::activityId).toList();
        Map<Long, Activity> activities = new HashMap<>();
        activityMapper.selectBatchIds(ids).stream()
                .filter(activity -> ActivityStatus.PUBLISHED.name().equals(activity.getStatus()))
                .forEach(activity -> activities.put(activity.getId(), activity));
        List<PopularActivityView> result = new ArrayList<>(counts.size());
        for (ActivityLikeCountView count : counts) {
            Activity activity = activities.get(count.activityId());
            if (activity != null) {
                result.add(new PopularActivityView(result.size() + 1, ActivityView.from(activity), count.likeCount()));
            }
        }
        return result;
    }
}
