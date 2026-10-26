package com.ticketing.mapper;

import com.ticketing.entity.OutboxEvent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface OutboxMapper extends BaseMapper<OutboxEvent> {
    @Select("SELECT COALESCE(MAX(id),0) FROM outbox_event WHERE aggregate_type='TICKET_SKU' AND aggregate_id=#{skuId} AND event_type='WAITLIST_DISPATCH_REQUESTED'")
    long latestInventorySequence(long skuId);

    @Select("SELECT aggregate_id,MAX(id) AS id FROM outbox_event WHERE aggregate_type='TICKET_SKU' AND event_type='WAITLIST_DISPATCH_REQUESTED' AND aggregate_id>#{after} GROUP BY aggregate_id ORDER BY aggregate_id LIMIT #{size}")
    List<OutboxEvent> inventorySignals(@Param("after") long after, @Param("size") int size);

    @Select("SELECT t.id FROM ticket_sku t JOIN activity_session s ON s.id=t.session_id WHERE s.activity_id=#{activityId} ORDER BY t.id")
    List<Long> inventorySkusForActivity(long activityId);

    @Select("SELECT id FROM ticket_sku WHERE session_id=#{sessionId} ORDER BY id")
    List<Long> inventorySkusForSession(long sessionId);

    @Insert("""
        INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,status,retry_count)
        VALUES(#{eventId},#{aggregateType},#{aggregateId},#{eventType},#{payload},#{status},#{retryCount})
        ON DUPLICATE KEY UPDATE event_id=VALUES(event_id)
        """)
    int insertIdempotent(OutboxEvent event);

    @Select("""
            SELECT *,UTC_TIMESTAMP(6) AS claim_observed_at FROM outbox_event WHERE event_type=#{type}
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
