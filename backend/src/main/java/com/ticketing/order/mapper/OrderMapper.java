package com.ticketing.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.order.entity.TicketOrder;
import com.ticketing.ticket.entity.TicketSku;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper extends BaseMapper<TicketOrder> {
    String VIEW = """
            SELECT o.*,i.sku_id,i.quantity,i.unit_price_cents,i.sku_name_snapshot,
                   s.title AS session_title,s.starts_at AS session_starts_at,a.id AS activity_id,a.title AS activity_title
            FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
            JOIN activity_session s ON s.id=o.session_id JOIN activity a ON a.id=s.activity_id
            """;

    @Select(VIEW + " WHERE o.id=#{id} AND o.user_id=#{userId}")
    TicketOrder owned(@Param("id") long id, @Param("userId") long userId);

    @Select(VIEW + " WHERE o.user_id=#{userId} ORDER BY o.created_at DESC,o.id DESC LIMIT #{size} OFFSET #{offset}")
    List<TicketOrder> listOwned(@Param("userId") long userId, @Param("size") int size, @Param("offset") long offset);

    @Select("""
            SELECT COALESCE(SUM(i.quantity),0) FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
            WHERE o.user_id=#{userId} AND o.session_id=#{sessionId} AND o.status IN ('PENDING','PAID')
            """)
    long occupied(@Param("userId") long userId, @Param("sessionId") long sessionId);

    @Update("""
            UPDATE ticket_sku SET locked_stock=locked_stock+#{quantity},version=version+1,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND status='ON_SALE' AND sale_starts_at<=UTC_TIMESTAMP(6) AND sale_ends_at>UTC_TIMESTAMP(6)
              AND total_stock-locked_stock-sold_stock>=#{quantity}
            """)
    int reserve(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update("""
            UPDATE ticket_sku SET locked_stock=locked_stock-#{quantity},version=version+1,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND locked_stock>=#{quantity}
            """)
    int release(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Insert("""
            INSERT INTO ticket_order_item(order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot)
            VALUES(#{order.id},#{sku.id},#{sku.sessionId},#{quantity},#{sku.priceCents},#{sku.name})
            """)
    int insertItem(@Param("order") TicketOrder order, @Param("sku") TicketSku sku, @Param("quantity") int quantity);
}
