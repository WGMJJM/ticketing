package com.ticketing.mapper;

import com.ticketing.entity.PurchaseRequest;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PurchaseRequestMapper {
    String COLUMNS = "id,user_id,sku_id,quantity,idempotency_key,request_hash,stock_reservation_token,status,order_id,failure_code,"
            + "accepted_at,deadline_at,processing_lease_until,processing_token,retry_count,next_recovery_at,updated_at";

    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE user_id=#{userId} AND idempotency_operation='CREATE_ORDER' AND idempotency_key=#{key}")
    PurchaseRequest byKey(@Param("userId") long userId, @Param("key") String key);
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE id=#{id}")
    PurchaseRequest find(long id);
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE stock_reservation_token=#{token}")
    PurchaseRequest byReservationToken(@Param("token") byte[] token);
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE id=#{id} FOR UPDATE")
    PurchaseRequest lock(long id);
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE user_id=#{userId} AND status IN('QUEUED','PROCESSING') ORDER BY id DESC LIMIT 50")
    List<PurchaseRequest> active(long userId);
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE sku_id=#{skuId} AND status IN('QUEUED','PROCESSING') ORDER BY id")
    List<PurchaseRequest> activeForSku(long skuId);
    @Select("SELECT " + COLUMNS + " FROM purchase_request WHERE status IN('QUEUED','PROCESSING') ORDER BY id LIMIT #{size}")
    List<PurchaseRequest> activeReservations(@Param("size") int size);
    @Select("SELECT UTC_TIMESTAMP(6)") LocalDateTime now();

    @Insert("""
        INSERT INTO purchase_request(user_id,sku_id,quantity,idempotency_key,request_hash,stock_reservation_token,deadline_at,next_recovery_at)
        VALUES(#{userId},#{skuId},#{quantity},#{key},#{hash},#{reservationToken},TIMESTAMPADD(SECOND,#{seconds},UTC_TIMESTAMP(6)),
          TIMESTAMPADD(SECOND,#{recovery},UTC_TIMESTAMP(6)))
        """)
    int insert(@Param("userId") long userId, @Param("skuId") long skuId, @Param("quantity") int quantity,
            @Param("key") String key, @Param("hash") byte[] hash, @Param("reservationToken") byte[] reservationToken,
            @Param("seconds") int seconds,
            @Param("recovery") int recoverySeconds);
    @Insert("""
        INSERT INTO purchase_request(user_id,sku_id,quantity,idempotency_key,request_hash,stock_reservation_token,
          status,failure_code,accepted_at,deadline_at,next_recovery_at,updated_at)
        VALUES(#{userId},#{skuId},#{quantity},#{key},#{hash},#{reservationToken},'FAILED',#{failure},
          UTC_TIMESTAMP(6),TIMESTAMPADD(SECOND,1,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        """)
    int insertTerminal(@Param("userId") long userId, @Param("skuId") long skuId, @Param("quantity") int quantity,
            @Param("key") String key, @Param("hash") byte[] hash, @Param("reservationToken") byte[] reservationToken,
            @Param("failure") String failure);
    @Select("SELECT LAST_INSERT_ID()") long insertedId();
    @Update("""
        UPDATE purchase_request SET status='PROCESSING',processing_token=processing_token+1,retry_count=retry_count+1,
          processing_lease_until=TIMESTAMPADD(SECOND,#{lease},UTC_TIMESTAMP(6)),
          next_recovery_at=TIMESTAMPADD(SECOND,#{lease},UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6)
        WHERE id=#{id} AND deadline_at>UTC_TIMESTAMP(6)
          AND (status='QUEUED' OR (status='PROCESSING' AND processing_lease_until<=UTC_TIMESTAMP(6)))
        """)
    int claim(@Param("id") long id, @Param("lease") int lease);

    @Update("""
        UPDATE purchase_request SET status=#{status},order_id=#{orderId},failure_code=#{code},
          processing_lease_until=NULL,updated_at=UTC_TIMESTAMP(6)
        WHERE id=#{id} AND status='PROCESSING' AND processing_token=#{token}
          AND processing_lease_until>UTC_TIMESTAMP(6) AND deadline_at>UTC_TIMESTAMP(6)
        """)
    int finish(@Param("id") long id, @Param("token") long token, @Param("status") String status,
            @Param("orderId") Long orderId, @Param("code") String code);
    @Update("""
        UPDATE purchase_request SET status='EXPIRED',failure_code='QUEUE_EXPIRED',processing_lease_until=NULL,
          processing_token=processing_token+1,updated_at=UTC_TIMESTAMP(6)
        WHERE id=#{id} AND status IN('QUEUED','PROCESSING') AND deadline_at<=UTC_TIMESTAMP(6)
        """)
    int expire(long id);

    @Update("""
        UPDATE purchase_request SET status='FAILED',failure_code='PURCHASE_COMMAND_DELIVERY_EXHAUSTED',
          processing_lease_until=NULL,processing_token=processing_token+1,updated_at=UTC_TIMESTAMP(6)
        WHERE id=#{id} AND order_id IS NULL AND deadline_at>UTC_TIMESTAMP(6)
          AND (status='QUEUED' OR (status='PROCESSING' AND processing_lease_until<=UTC_TIMESTAMP(6)))
        """)
    int failDeliveryExhausted(long id);

    @Select("""
        <script>SELECT id FROM purchase_request WHERE status IN('QUEUED','PROCESSING') AND id>#{after}
          AND (deadline_at&lt;=UTC_TIMESTAMP(6) OR next_recovery_at&lt;=UTC_TIMESTAMP(6))
        ORDER BY id LIMIT #{size}</script>
        """)
    List<Long> recoverable(@Param("after") long after, @Param("size") int size);
    @Update("""
        UPDATE purchase_request SET next_recovery_at=TIMESTAMPADD(SECOND,#{seconds},UTC_TIMESTAMP(6))
        WHERE id=#{id} AND status IN('QUEUED','PROCESSING') AND next_recovery_at<=UTC_TIMESTAMP(6)
          AND (status='QUEUED' OR processing_lease_until<=UTC_TIMESTAMP(6))
        """)
    int scheduleRecovery(@Param("id") long id, @Param("seconds") int seconds);
}
