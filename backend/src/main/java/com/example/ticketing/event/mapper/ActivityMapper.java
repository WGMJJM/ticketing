package com.example.ticketing.event.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ticketing.event.entity.Activity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {
}
