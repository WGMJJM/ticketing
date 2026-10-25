package com.ticketing.mapper;

import com.ticketing.entity.PaymentRecord;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentMapper extends BaseMapper<PaymentRecord> {}
