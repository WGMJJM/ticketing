package com.ticketing.waitlist.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("waitlist_entry")
public class WaitlistEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long skuId;
    private Integer quantity;
    private String status;
    private Long reservedOrderId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long value) { skuId = value; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer value) { quantity = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getReservedOrderId() { return reservedOrderId; }
    public void setReservedOrderId(Long value) { reservedOrderId = value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
