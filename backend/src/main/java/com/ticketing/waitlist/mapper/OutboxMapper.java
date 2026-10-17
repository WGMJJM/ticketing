package com.ticketing.waitlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.waitlist.entity.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OutboxMapper extends BaseMapper<OutboxEvent> {
    @Select("""
            SELECT * FROM outbox_event WHERE event_type='WAITLIST_DISPATCH_REQUESTED'
              AND status IN ('PENDING','PROCESSING') AND next_retry_at<=UTC_TIMESTAMP(6)
            ORDER BY next_retry_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    OutboxEvent claimCandidate();

    @Update("""
            UPDATE outbox_event SET status='PROCESSING',retry_count=retry_count+1,
              next_retry_at=TIMESTAMPADD(SECOND,#{lease},UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id}
            """)
    int claim(@Param("id") long id, @Param("lease") int lease);

    @Update("""
            UPDATE outbox_event SET status='SENT',sent_at=UTC_TIMESTAMP(6),last_error=NULL,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND status='PROCESSING' AND retry_count=#{attempt}
            """)
    int sent(@Param("id") long id, @Param("attempt") int attempt);

    @Update("""
            UPDATE outbox_event SET status=#{status},last_error=#{error},
              next_retry_at=TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND status='PROCESSING' AND retry_count=#{attempt}
            """)
    int failed(@Param("id") long id, @Param("attempt") int attempt, @Param("status") String status,
            @Param("delay") int delay, @Param("error") String error);
}
