package com.ticketing.messaging;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface OutboxMapper extends BaseMapper<OutboxEvent> {
    @Select("""
            SELECT * FROM outbox_event WHERE event_type=#{type}
              AND status IN ('PENDING','PROCESSING') AND next_retry_at<=UTC_TIMESTAMP(6)
            ORDER BY next_retry_at,id LIMIT #{size} FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEvent> claimCandidates(@Param("type") String type, @Param("size") int size);

    @Update("""
            <script>UPDATE outbox_event SET status='PROCESSING',retry_count=retry_count+1,
              next_retry_at=TIMESTAMPADD(SECOND,#{lease},UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6)
            WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int claimBatch(@Param("ids") List<Long> ids, @Param("lease") int lease);

    @Update("""
        UPDATE outbox_event SET status='PENDING',sent_at=NULL,next_retry_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)
        WHERE event_id=#{eventId} AND event_type='PURCHASE_REQUEST_ACCEPTED' AND status='SENT'
        """)
    int recoverPurchase(String eventId);

    @Update("""
        UPDATE outbox_event SET status='FAILED',retry_count=retry_count+1,last_error='购票排队已结束，无需继续投递',
          updated_at=UTC_TIMESTAMP(6)
        WHERE event_id=#{eventId} AND event_type='PURCHASE_REQUEST_ACCEPTED' AND status IN('PENDING','PROCESSING')
        """)
    int closePurchase(String eventId);

    @Update(
            """
            UPDATE outbox_event SET status='SENT',sent_at=UTC_TIMESTAMP(6),last_error=NULL,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND status='PROCESSING' AND retry_count=#{attempt}
            """)
    int sent(@Param("id") long id, @Param("attempt") int attempt);

    @Update(
            """
            UPDATE outbox_event SET status=#{status},last_error=#{error},
              next_retry_at=TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND status='PROCESSING' AND retry_count=#{attempt}
            """)
    int failed(
            @Param("id") long id,
            @Param("attempt") int attempt,
            @Param("status") String status,
            @Param("delay") int delay,
            @Param("error") String error);
}
