package com.ticketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.entity.PaymentRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentMapper extends BaseMapper<PaymentRecord> {}
