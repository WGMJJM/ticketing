package com.ticketing.mapper;

import com.ticketing.vo.ActivityEngagementView;
import com.ticketing.vo.ActivityLikeCountView;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ActivitySocialMapper {
    @Insert("INSERT IGNORE INTO activity_like (user_id, activity_id) VALUES (#{userId}, #{activityId})")
    int insertLike(@Param("userId") long userId, @Param("activityId") long activityId);

    @Delete("DELETE FROM activity_like WHERE user_id = #{userId} AND activity_id = #{activityId}")
    int deleteLike(@Param("userId") long userId, @Param("activityId") long activityId);

    @Insert("INSERT IGNORE INTO activity_follow (user_id, activity_id) VALUES (#{userId}, #{activityId})")
    int insertFollow(@Param("userId") long userId, @Param("activityId") long activityId);

    @Delete("DELETE FROM activity_follow WHERE user_id = #{userId} AND activity_id = #{activityId}")
    int deleteFollow(@Param("userId") long userId, @Param("activityId") long activityId);

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM activity_like WHERE activity_id = #{activityId}) AS like_count,
              (SELECT COUNT(*) FROM activity_follow WHERE activity_id = #{activityId}) AS follow_count,
              (#{userId} IS NOT NULL AND EXISTS (SELECT 1 FROM activity_like WHERE activity_id = #{activityId} AND user_id = #{userId})) AS liked,
              (#{userId} IS NOT NULL AND EXISTS (SELECT 1 FROM activity_follow WHERE activity_id = #{activityId} AND user_id = #{userId})) AS following
            """)
    @ConstructorArgs({
            @Arg(column = "like_count", javaType = long.class),
            @Arg(column = "follow_count", javaType = long.class),
            @Arg(column = "liked", javaType = boolean.class),
            @Arg(column = "following", javaType = boolean.class)
    })
    ActivityEngagementView engagement(@Param("activityId") long activityId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM activity_follow WHERE user_id = #{userId}")
    long countFollowed(@Param("userId") long userId);

    @Select("""
            SELECT activity_id
            FROM activity_follow
            WHERE user_id = #{userId}
            ORDER BY created_at DESC, activity_id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Long> selectFollowedIds(@Param("userId") long userId, @Param("limit") int limit,
                                 @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM activity WHERE status = 'PUBLISHED'")
    long countPublishedActivities();

    @Select("""
            SELECT a.id AS activity_id, COUNT(l.user_id) AS like_count
            FROM activity a LEFT JOIN activity_like l ON l.activity_id = a.id
            WHERE a.status = 'PUBLISHED'
            GROUP BY a.id
            ORDER BY like_count DESC, CAST(a.id AS CHAR) DESC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "activity_id", javaType = Long.class),
            @Arg(column = "like_count", javaType = long.class)
    })
    List<ActivityLikeCountView> selectPopular(@Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(id), 0) FROM activity WHERE status = 'PUBLISHED'")
    long maxPublishedId();

    @Select("""
            SELECT a.id AS activity_id, COUNT(l.user_id) AS like_count
            FROM activity a LEFT JOIN activity_like l ON l.activity_id = a.id
            WHERE a.status = 'PUBLISHED' AND a.id > #{afterId} AND a.id <= #{maxId}
            GROUP BY a.id
            ORDER BY a.id ASC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "activity_id", javaType = Long.class),
            @Arg(column = "like_count", javaType = long.class)
    })
    List<ActivityLikeCountView> selectPublishedCountsAfter(@Param("afterId") long afterId,
                                                            @Param("maxId") long maxId,
                                                            @Param("limit") int limit);
}
