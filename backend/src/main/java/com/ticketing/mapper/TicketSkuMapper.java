package com.ticketing.mapper;

import com.ticketing.entity.TicketSku;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface TicketSkuMapper extends BaseMapper<TicketSku> {
    @Select("SELECT redis_inventory_initialized FROM ticket_sku WHERE id=#{id}")
    boolean redisInitialized(long id);
    @Select("SELECT COUNT(*) FROM ticket_sku WHERE redis_inventory_initialized=TRUE")
    long redisInitializedCount();
    @Update("UPDATE ticket_sku SET redis_inventory_initialized=TRUE WHERE id=#{id}")
    int markRedisInitialized(long id);
    @Select("SELECT id FROM ticket_sku WHERE id>#{after} ORDER BY id LIMIT #{size}")
    List<Long> inventoryCandidates(@Param("after") long after, @Param("size") int size);
    @Update("""
            UPDATE ticket_sku SET sold_stock=sold_stock-#{quantity},updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND inventory_strategy='SINGLE' AND sold_stock>=#{quantity}
            """)
    int refund(@Param("skuId") long skuId, @Param("quantity") int quantity);
    @Update(
            """
            UPDATE ticket_sku SET locked_stock=locked_stock-#{quantity},sold_stock=sold_stock+#{quantity},
                   updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND inventory_strategy='SINGLE' AND locked_stock>=#{quantity}
              AND CAST(locked_stock AS SIGNED)+sold_stock<=total_stock
            """)
    int sell(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update(
            """
            UPDATE ticket_sku SET locked_stock=locked_stock+#{quantity},updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND inventory_strategy='SINGLE' AND status='ON_SALE' AND sale_starts_at<=UTC_TIMESTAMP(6) AND sale_ends_at>UTC_TIMESTAMP(6)
              AND total_stock-locked_stock-sold_stock>=#{quantity}
            """)
    int reserve(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update(
            """
            UPDATE ticket_sku SET locked_stock=locked_stock-#{quantity},updated_at=UTC_TIMESTAMP(6)
            WHERE id=#{skuId} AND inventory_strategy='SINGLE' AND locked_stock>=#{quantity}
            """)
    int release(@Param("skuId") long skuId, @Param("quantity") int quantity);
}
