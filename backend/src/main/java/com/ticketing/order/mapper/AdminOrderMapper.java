package com.ticketing.order.mapper;

import com.ticketing.order.dto.AdminOrderQuery;
import com.ticketing.order.dto.AdminOrderDetail.TicketCounts;
import com.ticketing.order.dto.AdminOrderDetail.WaitlistLink;
import com.ticketing.order.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 运营只读投影，不暴露其他模块Mapper或票券凭证。 */
@Mapper
public interface AdminOrderMapper {
    String FILTER = """
            <where>
              <if test="q.orderNo != null and q.orderNo != ''">AND o.order_no=#{q.orderNo}</if>
              <if test="q.userId != null">AND o.user_id=#{q.userId}</if>
              <if test="q.username != null and q.username != ''">AND u.username=#{q.username}</if>
              <if test="q.activityId != null">AND s.activity_id=#{q.activityId}</if>
              <if test="q.sessionId != null">AND o.session_id=#{q.sessionId}</if>
              <if test="q.status != null">AND o.status=#{q.status}</if>
              <if test="q.createdFrom != null">AND o.created_at&gt;=#{q.fromUtc}</if>
              <if test="q.createdTo != null">AND o.created_at&lt;#{q.toUtc}</if>
            </where>
            """;
    @Select("<script>" + OrderMapper.VIEW + FILTER
            + " ORDER BY o.created_at DESC,o.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TicketOrder> list(@Param("q") AdminOrderQuery query, @Param("size") int size, @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) FROM ticket_order o JOIN activity_session s ON s.id=o.session_id"
            + " JOIN user_account u ON u.id=o.user_id " + FILTER + "</script>")
    long count(@Param("q") AdminOrderQuery query);

    @Select("""
            SELECT COUNT(*) total,COALESCE(SUM(status='UNUSED'),0) unused,
              COALESCE(SUM(status='USED'),0) used,COALESCE(SUM(status='VOID'),0) voided
            FROM electronic_ticket WHERE order_id=#{orderId}
            """)
    TicketCounts tickets(long orderId);

    @Select("""
            SELECT CAST(id AS CHAR) id,status,CAST(reserved_order_id AS CHAR) reserved_order_id,
              CAST(sku_id AS CHAR) sku_id,quantity FROM waitlist_entry WHERE reserved_order_id=#{orderId}
            """)
    WaitlistLink waitlist(long orderId);
}
