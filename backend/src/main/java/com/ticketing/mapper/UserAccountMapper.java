package com.ticketing.mapper;

import com.ticketing.entity.UserAccount;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
    @Select("SELECT COALESCE(MAX(id),0) FROM user_account")
    long highestId();

    @Select("SELECT id,username,email FROM user_account WHERE id>#{after} AND id<=#{through} ORDER BY id LIMIT #{size}")
    List<UserAccount> registrationPage(@Param("after") long after, @Param("through") long through, @Param("size") int size);
}
