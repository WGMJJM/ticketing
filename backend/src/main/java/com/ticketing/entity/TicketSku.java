package com.ticketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("ticket_sku")
public class TicketSku {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String name;

    private Long priceCents;

    private Integer totalStock;

    private Integer lockedStock;

    private Integer soldStock;

    private String inventoryStrategy = "SINGLE";

    private Integer bucketCount = 0;

    private LocalDateTime saleStartsAt;

    private LocalDateTime saleEndsAt;

    private Integer maxQuantityPerOrder;

    private String status;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getPriceCents() { return priceCents; }
    public void setPriceCents(Long priceCents) { this.priceCents = priceCents; }

    public Integer getTotalStock() { return totalStock; }
    public void setTotalStock(Integer totalStock) { this.totalStock = totalStock; }

    public Integer getLockedStock() { return lockedStock; }
    public void setLockedStock(Integer lockedStock) { this.lockedStock = lockedStock; }

    public Integer getSoldStock() { return soldStock; }
    public void setSoldStock(Integer soldStock) { this.soldStock = soldStock; }

    public String getInventoryStrategy() { return inventoryStrategy; }
    public void setInventoryStrategy(String inventoryStrategy) { this.inventoryStrategy = inventoryStrategy; }
    public Integer getBucketCount() { return bucketCount; }
    public void setBucketCount(Integer bucketCount) { this.bucketCount = bucketCount; }
    public boolean bucketed() { return "BUCKETED".equals(inventoryStrategy); }

    public LocalDateTime getSaleStartsAt() { return saleStartsAt; }
    public void setSaleStartsAt(LocalDateTime saleStartsAt) { this.saleStartsAt = saleStartsAt; }

    public LocalDateTime getSaleEndsAt() { return saleEndsAt; }
    public void setSaleEndsAt(LocalDateTime saleEndsAt) { this.saleEndsAt = saleEndsAt; }

    public Integer getMaxQuantityPerOrder() { return maxQuantityPerOrder; }
    public void setMaxQuantityPerOrder(Integer maxQuantityPerOrder) { this.maxQuantityPerOrder = maxQuantityPerOrder; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
