package com.ticketing.inventory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TicketSkuMapper extends BaseMapper<TicketSku> {
    /** 三个主键点查，仅作新申请预检；不读展示缓存、不决定库存及最终建单。 */
    @Select("""
            SELECT IF(t.status='ON_SALE' AND a.status='PUBLISHED' AND s.status='SCHEDULED'
                AND t.sale_starts_at<=UTC_TIMESTAMP(6) AND t.sale_ends_at>UTC_TIMESTAMP(6)
                AND s.ends_at>UTC_TIMESTAMP(6),1,0)
            FROM ticket_sku t JOIN activity_session s ON s.id=t.session_id
            JOIN activity a ON a.id=s.activity_id WHERE t.id=#{skuId}
            """)
    Integer purchaseEligibility(long skuId);

    @Update("""
            UPDATE ticket_sku SET sold_stock=sold_stock-#{quantity},version=version+1,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND inventory_strategy='SINGLE' AND sold_stock>=#{quantity}
            """)
    int refund(@Param("skuId") long skuId, @Param("quantity") int quantity);
    @Update(
            """
            UPDATE ticket_sku SET locked_stock=locked_stock-#{quantity},sold_stock=sold_stock+#{quantity},
                   version=version+1,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND inventory_strategy='SINGLE' AND locked_stock>=#{quantity}
              AND CAST(locked_stock AS SIGNED)+sold_stock<=total_stock
            """)
    int sell(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update(
            """
            UPDATE ticket_sku SET locked_stock=locked_stock+#{quantity},version=version+1,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND inventory_strategy='SINGLE' AND status='ON_SALE' AND sale_starts_at<=UTC_TIMESTAMP(6) AND sale_ends_at>UTC_TIMESTAMP(6)
              AND total_stock-locked_stock-sold_stock>=#{quantity}
            """)
    int reserve(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update(
            """
            UPDATE ticket_sku SET locked_stock=locked_stock-#{quantity},version=version+1,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND inventory_strategy='SINGLE' AND locked_stock>=#{quantity}
            """)
    int release(@Param("skuId") long skuId, @Param("quantity") int quantity);
}
