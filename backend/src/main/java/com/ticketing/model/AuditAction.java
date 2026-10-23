package com.ticketing.model;

/** 固定管理操作域，数据库及API保存枚举名称。 */
public enum AuditAction {
    ACTIVITY_CREATE("创建活动"),
    ACTIVITY_UPDATE("修改活动"),
    ACTIVITY_PUBLISH("发布活动"),
    ACTIVITY_OFFLINE("下架活动"),
    SESSION_CREATE("新增场次"),
    SESSION_UPDATE("修改场次"),
    SESSION_CANCEL("取消场次"),
    TICKET_CREATE("新增票档"),
    TICKET_UPDATE("修改票档"),
    STOCK_ADJUST("调整总库存"),
    TICKET_PUBLISH("上架票档"),
    TICKET_OFFLINE("下架票档"),
    STAFF_PROMOTE("授权工作人员角色"),
    STAFF_DEMOTE("撤销工作人员角色"),
    STAFF_DISABLE("停用工作人员"),
    SESSION_GRANT("授权核销场次"),
    SESSION_REVOKE("撤销核销场次"),
    ORDER_REFUND("处理整单模拟退款");

    private final String label;

    AuditAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
