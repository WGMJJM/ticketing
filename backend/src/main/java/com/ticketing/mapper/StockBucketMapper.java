package com.ticketing.mapper;

import com.ticketing.entity.StockAllocation;
import com.ticketing.entity.StockBucket;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface StockBucketMapper {
    @Update("""
            UPDATE ticket_stock_bucket SET locked_stock=locked_stock+#{quantity}
            WHERE sku_id=#{sku} AND bucket_no=#{number}
              AND CAST(total_stock AS SIGNED)-locked_stock-sold_stock>=#{quantity}
            """)
    int reserve(@Param("sku") long sku, @Param("number") int number, @Param("quantity") int quantity);

    @Insert("""
            INSERT INTO order_stock_allocation(order_id,sku_id,bucket_no,quantity)
            VALUES(#{order},#{sku},#{number},#{quantity})
            """)
    int allocate(@Param("order") long order, @Param("sku") long sku,
            @Param("number") int number, @Param("quantity") int quantity);
    @Select("SELECT * FROM ticket_stock_bucket WHERE sku_id=#{sku} ORDER BY bucket_no")
    List<StockBucket> list(long sku);

    @Select("SELECT * FROM ticket_stock_bucket WHERE sku_id=#{sku} AND bucket_no=#{number} FOR UPDATE")
    StockBucket lock(@Param("sku") long sku, @Param("number") int number);

    @Select("SELECT * FROM order_stock_allocation WHERE order_id=#{orderId} ORDER BY sku_id,bucket_no")
    List<StockAllocation> allocations(long orderId);

    @Update("""
            UPDATE ticket_stock_bucket SET locked_stock=locked_stock-#{quantity},sold_stock=sold_stock+#{quantity}
            WHERE sku_id=#{sku} AND bucket_no=#{number} AND locked_stock>=#{quantity}
            """)
    int sell(@Param("sku") long sku, @Param("number") int number, @Param("quantity") int quantity);

    @Update("""
            UPDATE ticket_stock_bucket SET locked_stock=locked_stock-#{quantity}
            WHERE sku_id=#{sku} AND bucket_no=#{number} AND locked_stock>=#{quantity}
            """)
    int release(@Param("sku") long sku, @Param("number") int number, @Param("quantity") int quantity);

    @Update("""
            UPDATE ticket_stock_bucket SET sold_stock=sold_stock-#{quantity}
            WHERE sku_id=#{sku} AND bucket_no=#{number} AND sold_stock>=#{quantity}
            """)
    int refund(@Param("sku") long sku, @Param("number") int number, @Param("quantity") int quantity);

    @Update("""
            UPDATE ticket_stock_bucket SET total_stock=#{total}
            WHERE sku_id=#{sku} AND bucket_no=#{number} AND CAST(locked_stock AS SIGNED)+sold_stock<=#{total}
            """)
    int resize(@Param("sku") long sku, @Param("number") int number, @Param("total") int total);

}
