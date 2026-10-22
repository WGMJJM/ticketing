package com.ticketing.order.dto;

/** 人工处理不会伪装成退款成功，订单仍保留PAID与原票券。 */
public record RefundResult(String status, RefundView refund, AftercareView aftercare) {}
