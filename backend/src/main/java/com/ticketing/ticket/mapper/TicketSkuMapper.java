package com.ticketing.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.ticket.entity.TicketSku;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TicketSkuMapper extends BaseMapper<TicketSku> {
    @Update(
            """
            UPDATE ticket_sku SET locked_stock=locked_stock-#{quantity},sold_stock=sold_stock+#{quantity},
                   version=version+1,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND locked_stock>=#{quantity}
              AND CAST(locked_stock AS SIGNED)+sold_stock<=total_stock
            """)
    int sell(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update(
            """
            UPDATE ticket_sku SET locked_stock=locked_stock+#{quantity},version=version+1,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND status='ON_SALE' AND sale_starts_at<=UTC_TIMESTAMP(6) AND sale_ends_at>UTC_TIMESTAMP(6)
              AND total_stock-locked_stock-sold_stock>=#{quantity}
            """)
    int reserve(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update(
            """
            UPDATE ticket_sku SET locked_stock=locked_stock-#{quantity},version=version+1,updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND locked_stock>=#{quantity}
            """)
    int release(@Param("skuId") long skuId, @Param("quantity") int quantity);
}
