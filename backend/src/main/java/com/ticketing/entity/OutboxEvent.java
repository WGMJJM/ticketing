package com.ticketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("outbox_event")
public class OutboxEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;
    private String aggregateType;
    private Long aggregateId;
    private String eventType;
    private String payload;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        id = value;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String value) {
        eventId = value;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String value) {
        aggregateType = value;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Long value) {
        aggregateId = value;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String value) {
        eventType = value;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String value) {
        payload = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer value) {
        retryCount = value;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime value) {
        nextRetryAt = value;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String value) {
        lastError = value;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime value) {
        sentAt = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        createdAt = value;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime value) {
        updatedAt = value;
    }
}
