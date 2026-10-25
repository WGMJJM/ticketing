package com.ticketing.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface UserSessionQuotaMapper {
    @Insert("""
            INSERT INTO user_session_quota(user_id,session_id,occupied_quantity) VALUES(#{user},#{session},0)
            ON DUPLICATE KEY UPDATE occupied_quantity=occupied_quantity
            """)
    void lock(@Param("user") long userId, @Param("session") long sessionId);

    @Select("SELECT occupied_quantity FROM user_session_quota WHERE user_id=#{user} AND session_id=#{session}")
    Integer occupied(@Param("user") long userId, @Param("session") long sessionId);

    @Update("""
            UPDATE user_session_quota SET occupied_quantity=occupied_quantity+#{quantity},updated_at=UTC_TIMESTAMP(6)
            WHERE user_id=#{user} AND session_id=#{session} AND occupied_quantity<=#{maximum}-#{quantity}
            """)
    int occupy(@Param("user") long userId, @Param("session") long sessionId,
               @Param("quantity") int quantity, @Param("maximum") int maximum);

    @Update("""
            UPDATE user_session_quota SET occupied_quantity=occupied_quantity-#{quantity},updated_at=UTC_TIMESTAMP(6)
            WHERE user_id=#{user} AND session_id=#{session} AND occupied_quantity>=#{quantity}
            """)
    int release(@Param("user") long userId, @Param("session") long sessionId, @Param("quantity") int quantity);
}
