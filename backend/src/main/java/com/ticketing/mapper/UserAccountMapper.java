package com.ticketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}
