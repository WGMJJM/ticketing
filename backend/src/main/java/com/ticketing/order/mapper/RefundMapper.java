package com.ticketing.order.mapper;

import com.ticketing.order.dto.RefundView;
import com.ticketing.order.entity.PaymentRecord;
import com.ticketing.order.model.RefundReason;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RefundMapper {
    @Select("""
            SELECT CAST(r.order_id AS CHAR) order_id,r.refund_no,p.payment_no,
              CAST(r.amount_cents AS CHAR) amount_cents,CAST(r.operator_id AS CHAR) operator_id,
              r.reason,r.status,r.refunded_at,TRUE simulated
            FROM refund_record r JOIN payment_record p ON p.id=r.payment_id WHERE r.order_id=#{orderId}
            """)
    RefundView find(long orderId);

    @Insert("""
            INSERT INTO refund_record(order_id,refund_no,payment_id,amount_cents,operator_id,reason)
            VALUES(#{orderId},#{refundNo},#{payment.id},#{payment.amountCents},#{operatorId},#{reason})
            """)
    int insert(@Param("orderId") long orderId, @Param("refundNo") String refundNo,
            @Param("payment") PaymentRecord payment, @Param("operatorId") Long operatorId,
            @Param("reason") RefundReason reason);
}
