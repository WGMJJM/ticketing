package com.ticketing.mapper;

import com.ticketing.entity.Activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {
    default Activity selectByIdForShare(long id) {
        return selectOne(new LambdaQueryWrapper<Activity>().eq(Activity::getId, id).last("FOR SHARE"));
    }
}
