package com.ticketing.waitlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.waitlist.dto.WaitlistView;
import com.ticketing.waitlist.entity.WaitlistEntry;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WaitlistMapper extends BaseMapper<WaitlistEntry> {
    @Select("SELECT UTC_TIMESTAMP(6)")
    LocalDateTime databaseNow();

    @Update(
            "UPDATE waitlist_entry SET status=#{status},updated_at=UTC_TIMESTAMP(6) WHERE id=#{id}"
                    + " AND status='WAITING'")
    int finishWaiting(@Param("id") long id, @Param("status") String status);

    @Update(
            "UPDATE waitlist_entry SET status=#{status},updated_at=UTC_TIMESTAMP(6) WHERE id=#{id}"
                    + " AND status='ALLOCATED'")
    int finishAllocated(@Param("id") long id, @Param("status") String status);

    String VIEW =
            """
            SELECT CAST(w.id AS CHAR) AS id,CAST(w.sku_id AS CHAR) AS sku_id,CAST(s.id AS CHAR) AS session_id,
              CAST(a.id AS CHAR) AS activity_id,a.title AS activity_title,s.title AS session_title,t.name AS sku_name,
              CAST(t.price_cents AS CHAR) AS price_cents,w.quantity,w.status,w.created_at,w.expires_at,
              CAST(w.reserved_order_id AS CHAR) AS reserved_order_id,o.status AS order_status,o.expires_at AS order_expires_at,
              (w.status='WAITING') AS can_cancel
            FROM waitlist_entry w JOIN ticket_sku t ON t.id=w.sku_id JOIN activity_session s ON s.id=t.session_id
            JOIN activity a ON a.id=s.activity_id
            LEFT JOIN ticket_order o ON o.id=w.reserved_order_id AND o.user_id=w.user_id AND o.session_id=s.id
              AND EXISTS(SELECT 1 FROM ticket_order_item i WHERE i.order_id=o.id AND i.sku_id=w.sku_id AND i.quantity=w.quantity)
            """;

    @Select(VIEW + " WHERE w.id=#{id} AND w.user_id=#{userId}")
    WaitlistView owned(@Param("id") long id, @Param("userId") long userId);

    @Select(
            VIEW
                    + " WHERE w.user_id=#{userId} ORDER BY w.created_at DESC,w.id DESC LIMIT"
                    + " #{size} OFFSET #{offset}")
    List<WaitlistView> listOwned(
            @Param("userId") long userId, @Param("size") int size, @Param("offset") long offset);

    @Select(
            "SELECT * FROM waitlist_entry WHERE sku_id=#{skuId} AND status='WAITING' ORDER BY id"
                    + " LIMIT 1")
    WaitlistEntry head(@Param("skuId") long skuId);

    @Select(
            "SELECT DISTINCT sku_id FROM waitlist_entry WHERE status='WAITING' AND sku_id>#{after}"
                    + " ORDER BY sku_id LIMIT #{size}")
    List<Long> pendingSkus(@Param("after") long after, @Param("size") int size);

    @Update(
            """
            UPDATE waitlist_entry SET status='ALLOCATED',reserved_order_id=#{orderId},updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND status='WAITING' AND reserved_order_id IS NULL
            """)
    int allocate(@Param("id") long id, @Param("orderId") long orderId);
}
