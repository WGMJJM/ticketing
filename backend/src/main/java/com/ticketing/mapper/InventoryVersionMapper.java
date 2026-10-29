package com.ticketing.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InventoryVersionMapper {
    @Select("SELECT COALESCE((SELECT inventory_version FROM inventory_sync_version WHERE sku_id=#{skuId}),0)")
    long current(long skuId);

    @Insert("""
            INSERT INTO inventory_sync_version(sku_id,inventory_version) VALUES(#{skuId},1)
            ON DUPLICATE KEY UPDATE inventory_version=inventory_version+1
            """)
    int increment(long skuId);

    @Select("SELECT t.id FROM ticket_sku t JOIN activity_session s ON s.id=t.session_id WHERE s.activity_id=#{activityId} ORDER BY t.id")
    List<Long> forActivity(long activityId);

    @Select("SELECT id FROM ticket_sku WHERE session_id=#{sessionId} ORDER BY id")
    List<Long> forSession(long sessionId);
}
