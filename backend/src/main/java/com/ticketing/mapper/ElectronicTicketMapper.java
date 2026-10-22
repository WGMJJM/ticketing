package com.ticketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.dto.ElectronicTicketView;
import com.ticketing.entity.ElectronicTicket;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ElectronicTicketMapper extends BaseMapper<ElectronicTicket> {
    String VIEW =
            """
            SELECT CAST(t.id AS CHAR) AS id,CAST(t.order_id AS CHAR) AS order_id,
              CAST(t.order_item_id AS CHAR) AS order_item_id,CAST(t.sku_id AS CHAR) AS sku_id,
              CAST(t.owner_id AS CHAR) AS owner_id,u.username AS owner_name,t.ticket_no,t.sequence_no,t.status,
              CAST(a.id AS CHAR) AS activity_id,a.title AS activity_title,CAST(s.id AS CHAR) AS session_id,
              s.title AS session_title,s.starts_at AS session_starts_at,s.ends_at AS session_ends_at,
              a.city,a.venue,i.sku_name_snapshot AS sku_name,t.used_at,CAST(t.checked_by AS CHAR) AS checked_by,t.created_at
            FROM electronic_ticket t JOIN ticket_order o ON o.id=t.order_id AND o.user_id=t.owner_id
            JOIN ticket_order_item i ON i.id=t.order_item_id AND i.order_id=t.order_id AND i.sku_id=t.sku_id
            JOIN activity_session s ON s.id=o.session_id JOIN activity a ON a.id=s.activity_id
            JOIN user_account u ON u.id=t.owner_id
            """;
    String OWNED_FILTER =
            """
            WHERE t.owner_id=#{ownerId}
            <if test="orderId != null">AND t.order_id=#{orderId}</if>
            <if test="status != null">AND t.status=#{status}</if>
            """;

    @Select(
            "<script>"
                    + VIEW
                    + OWNED_FILTER
                    + " ORDER BY t.created_at DESC,t.id DESC LIMIT #{size} OFFSET"
                    + " #{offset}</script>")
    List<ElectronicTicketView> listOwned(
            @Param("ownerId") long ownerId,
            @Param("orderId") Long orderId,
            @Param("status") String status,
            @Param("size") int size,
            @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) FROM electronic_ticket t " + OWNED_FILTER + "</script>")
    long countOwned(
            @Param("ownerId") long ownerId,
            @Param("orderId") Long orderId,
            @Param("status") String status);

    @Select(VIEW + " WHERE t.id=#{id} AND t.owner_id=#{ownerId}")
    ElectronicTicketView owned(@Param("id") long id, @Param("ownerId") long ownerId);

    @Select(VIEW + " WHERE t.id=#{id}")
    ElectronicTicketView view(@Param("id") long id);

    @Select(
            """
            SELECT o.id AS order_id,o.user_id AS owner_id,i.id AS order_item_id,i.sku_id,i.quantity,o.status
            FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id WHERE o.id=#{orderId}
            """)
    IssuanceSource source(@Param("orderId") long orderId);

    @Select(
            "SELECT * FROM electronic_ticket WHERE order_id=#{orderId} ORDER BY sequence_no FOR"
                    + " UPDATE")
    List<ElectronicTicket> lockIssued(@Param("orderId") long orderId);

    @Update("""
            UPDATE electronic_ticket SET status='VOID',updated_at=UTC_TIMESTAMP(6)
            WHERE order_id=#{orderId} AND status='UNUSED'
            """)
    int voidUnused(long orderId);

    @Select(
            """
            SELECT o.id FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
            WHERE o.status='PAID' AND o.id>#{after} ORDER BY o.id LIMIT #{size}
            """)
    List<Long> paidCandidates(@Param("after") long after, @Param("size") int size);

    @Update(
            """
            UPDATE electronic_ticket t SET t.status='USED',t.used_at=UTC_TIMESTAMP(6),t.checked_by=#{adminId},t.updated_at=UTC_TIMESTAMP(6)
            WHERE t.id=#{id} AND t.status='UNUSED'
              AND EXISTS(SELECT 1 FROM ticket_order o JOIN activity_session s ON s.id=o.session_id
                WHERE o.id=t.order_id AND o.status='PAID' AND s.status='SCHEDULED'
                  AND UTC_TIMESTAMP(6)>=TIMESTAMPADD(MINUTE,-#{opensMinutes},s.starts_at)
                  AND UTC_TIMESTAMP(6)<s.ends_at)
            """)
    int checkIn(
            @Param("id") long id,
            @Param("adminId") long adminId,
            @Param("opensMinutes") int opensMinutes);

    record IssuanceSource(
            long orderId,
            long ownerId,
            long orderItemId,
            long skuId,
            int quantity,
            String status) {}
}
