package com.ticketing.messaging;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** 仅JFR录制开启时记录技术投递关联；无数据库写入、无业务载荷、无高基数指标标签。 */
@Name("com.ticketing.DeliveryObservation")
@Label("购票消息阶段")
@Category("Ticketing")
@StackTrace(false)
public class DeliveryObservation extends Event {
    public String deliveryKey;
    public String stage;
    public long monotonicNanos;

    public static void record(String key, String stage) { record(key, stage, System.nanoTime()); }

    public static void record(String key, String stage, long observedAt) {
        if (key == null) return;
        var event = new DeliveryObservation();
        if (!event.isEnabled()) return;
        event.deliveryKey = key;
        event.stage = stage;
        event.monotonicNanos = observedAt;
        event.commit();
    }
}
