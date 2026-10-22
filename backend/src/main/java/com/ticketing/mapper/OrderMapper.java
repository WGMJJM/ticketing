package com.ticketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.entity.TicketOrder;
import com.ticketing.model.TicketSale;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<TicketOrder> {
    String VIEW =
            """
            SELECT o.*,i.sku_id,i.quantity,i.unit_price_cents,i.sku_name_snapshot,
                   s.title AS session_title,s.starts_at AS session_starts_at,s.status AS session_status,
                   a.id AS activity_id,a.title AS activity_title,a.status AS activity_status,
                   p.payment_no,p.amount_cents AS payment_amount_cents,p.paid_at AS payment_paid_at,
                   u.username,r.refund_no,r.reason AS refund_reason,r.refunded_at
            FROM ticket_order o LEFT JOIN ticket_order_item i ON i.order_id=o.id
            JOIN activity_session s ON s.id=o.session_id JOIN activity a ON a.id=s.activity_id
            LEFT JOIN payment_record p ON p.order_id=o.id AND p.status='SUCCEEDED'
            JOIN user_account u ON u.id=o.user_id
            LEFT JOIN refund_record r ON r.order_id=o.id
            """;

    @Select(VIEW + " WHERE o.id=#{id} AND o.user_id=#{userId}")
    TicketOrder owned(@Param("id") long id, @Param("userId") long userId);

    @Select(VIEW + " WHERE o.id=#{id}")
    TicketOrder detail(long id);

    @Update("""
            UPDATE ticket_order SET status='REFUNDED',updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND status='PAID' AND paid_at IS NOT NULL
            """)
    int markRefunded(long id);

    @Select(
            VIEW
                    + " WHERE o.user_id=#{userId} ORDER BY o.created_at DESC,o.id DESC LIMIT"
                    + " #{size} OFFSET #{offset}")
    List<TicketOrder> listOwned(
            @Param("userId") long userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT UTC_TIMESTAMP(6)")
    LocalDateTime databaseNow();

    @Select(
            """
            <script>
            SELECT id,user_id,expires_at FROM ticket_order FORCE INDEX (idx_order_expiry)
            WHERE status='PENDING' AND expires_at&lt;=UTC_TIMESTAMP(6)
            <if test="after != null">
              AND (expires_at>#{after} OR (expires_at=#{after} AND id>#{afterId}))
            </if>
            ORDER BY expires_at,id LIMIT #{size}
            </script>
            """)
    List<TicketOrder> expiredCandidates(
            @Param("size") int size,
            @Param("after") LocalDateTime after,
            @Param("afterId") long afterId);

    @Update(
            """
            UPDATE ticket_order SET status='CANCELLED',updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND user_id=#{userId} AND status='PENDING'
            """)
    int markCancelled(@Param("id") long id, @Param("userId") long userId);

    @Update(
            """
            UPDATE ticket_order SET status='PAID',paid_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND user_id=#{userId} AND status='PENDING' AND expires_at>UTC_TIMESTAMP(6)
            """)
    int markPaid(@Param("id") long id, @Param("userId") long userId);

    @Update(
            """
            UPDATE ticket_order SET status='EXPIRED',updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND user_id=#{userId} AND status='PENDING' AND expires_at<=UTC_TIMESTAMP(6)
            """)
    int markExpired(@Param("id") long id, @Param("userId") long userId);

    @Select(
            """
            SELECT COALESCE(SUM(i.quantity),0) FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
            WHERE o.user_id=#{userId} AND o.session_id=#{sessionId} AND o.status IN ('PENDING','PAID')
            """)
    long occupied(@Param("userId") long userId, @Param("sessionId") long sessionId);

    @Insert(
            """
            INSERT INTO ticket_order_item(order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot)
            VALUES(#{order.id},#{sku.skuId},#{sku.sessionId},#{quantity},#{sku.priceCents},#{sku.name})
            """)
    int insertItem(
            @Param("order") TicketOrder order,
            @Param("sku") TicketSale sku,
            @Param("quantity") int quantity);
}
