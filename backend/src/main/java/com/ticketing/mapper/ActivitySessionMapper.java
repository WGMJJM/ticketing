package com.ticketing.mapper;

import com.ticketing.entity.ActivitySession;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ActivitySessionMapper extends BaseMapper<ActivitySession> {
    @Select("<script>SELECT * FROM activity_session WHERE activity_id IN "
            + "<foreach collection='activityIds' item='activityId' open='(' separator=',' close=')'>"
            + "#{activityId}</foreach> ORDER BY starts_at ASC, id ASC</script>")
    List<ActivitySession> selectByActivityIds(@Param("activityIds") List<Long> activityIds);
}
