package com.ticketing.mapper;

import com.ticketing.entity.PurchaseRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PurchaseRequestMapper {
    String COLUMNS = "id,user_id,sku_id,quantity,idempotency_key,request_hash,status,order_id,failure_code,"
            + "accepted_at,deadline_at,updated_at,idempotency_operation,waitlist_entry_id";
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE id=#{id}")
    PurchaseRequest find(long id);
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE user_id=#{userId} AND idempotency_operation='CREATE_ORDER' AND idempotency_key=#{key}")
    PurchaseRequest byKey(@Param("userId") long userId, @Param("key") String key);
    /** 消费和过期仲裁共用唯一键及行锁；冲突只锁原事实，不吞事务异常。 */
    @Insert("""
        INSERT INTO purchase_request(id,user_id,sku_id,quantity,idempotency_key,request_hash,accepted_at,deadline_at,idempotency_operation,waitlist_entry_id)
        VALUES(#{id},#{userId},#{skuId},#{quantity},#{idempotencyKey},#{requestHash},#{acceptedAt},#{deadlineAt},#{idempotencyOperation},#{waitlistEntryId})
        ON DUPLICATE KEY UPDATE id=purchase_request.id
        """)
    int coordinate(PurchaseRequest request);
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE id=#{id} FOR UPDATE")
    PurchaseRequest lock(long id);
    @Update("""
        UPDATE purchase_request SET status=#{status},order_id=#{orderId},failure_code=#{code},updated_at=UTC_TIMESTAMP(6)
        WHERE id=#{id} AND status='QUEUED'
        """)
    int finish(@Param("id") long id, @Param("status") String status,
            @Param("orderId") Long orderId, @Param("code") String code);
    @Select("SELECT UTC_TIMESTAMP(6)")
    LocalDateTime now();
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE sku_id=#{skuId} AND id>#{after} ORDER BY id LIMIT 200")
    List<PurchaseRequest> history(@Param("skuId") long skuId, @Param("after") long after);
}
