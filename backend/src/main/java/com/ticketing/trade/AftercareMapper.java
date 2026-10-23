package com.ticketing.trade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AftercareMapper {
    @Select("""
            SELECT status,reason_code,message,attempt_count,last_attempt_at,next_retry_at
            FROM order_aftercare WHERE order_id=#{orderId}
            """)
    AftercareView find(long orderId);

    @Insert("""
            INSERT INTO order_aftercare(order_id,status,reason_code,message)
            VALUES(#{id},#{status},#{code},#{message})
            ON DUPLICATE KEY UPDATE status=#{status},reason_code=#{code},message=#{message},
              attempt_count=attempt_count+1,last_attempt_at=UTC_TIMESTAMP(6),next_retry_at=NULL
            """)
    int record(@Param("id") long id, @Param("status") String status,
            @Param("code") String code, @Param("message") String message);

    @Insert("""
            INSERT INTO order_aftercare(order_id,status,reason_code,message,next_retry_at)
            VALUES(#{id},'RETRY','PROCESSING_FAILED','善后处理暂未完成，将退避重试',TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(6)))
            ON DUPLICATE KEY UPDATE
              next_retry_at=IF(attempt_count+1>=#{max},NULL,TIMESTAMPADD(SECOND,LEAST(3600,#{delay}*POW(2,attempt_count)),UTC_TIMESTAMP(6))),
              status=IF(attempt_count+1>=#{max},'MANUAL_REQUIRED','RETRY'),
              reason_code='PROCESSING_FAILED',message='善后处理失败，请查看状态或联系运营人员',
              attempt_count=attempt_count+1,last_attempt_at=UTC_TIMESTAMP(6)
            """)
    int failure(@Param("id") long id, @Param("delay") int delay, @Param("max") int max);

    @Select("""
            SELECT o.id,o.user_id FROM ticket_order o
            JOIN activity_session s ON s.id=o.session_id
            LEFT JOIN order_aftercare c ON c.order_id=o.id
            WHERE s.status='CANCELLED' AND o.status IN ('PENDING','PAID') AND o.id>#{after}
              AND (c.order_id IS NULL OR (c.status='RETRY' AND c.next_retry_at<=UTC_TIMESTAMP(6)))
            ORDER BY o.id LIMIT #{size}
            """)
    List<Candidate> candidates(@Param("after") long after, @Param("size") int size);

    record Candidate(long id, long userId) {}
}
