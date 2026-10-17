package com.ticketing.checkin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("electronic_ticket")
public class ElectronicTicket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ticketNo;
    private Long orderId;
    private Long orderItemId;
    private Long skuId;
    private Long ownerId;
    private Integer sequenceNo;
    private byte[] credentialDigest;
    private Integer credentialKeyVersion;
    private String status;
    private LocalDateTime usedAt;
    private Long checkedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getTicketNo() { return ticketNo; }
    public void setTicketNo(String value) { ticketNo = value; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long value) { orderId = value; }
    public Long getOrderItemId() { return orderItemId; }
    public void setOrderItemId(Long value) { orderItemId = value; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long value) { skuId = value; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long value) { ownerId = value; }
    public Integer getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Integer value) { sequenceNo = value; }
    public byte[] getCredentialDigest() { return credentialDigest; }
    public void setCredentialDigest(byte[] value) { credentialDigest = value; }
    public Integer getCredentialKeyVersion() { return credentialKeyVersion; }
    public void setCredentialKeyVersion(Integer value) { credentialKeyVersion = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime value) { usedAt = value; }
    public Long getCheckedBy() { return checkedBy; }
    public void setCheckedBy(Long value) { checkedBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
