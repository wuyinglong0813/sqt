package com.tradepass.framework.audit.core;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.framework.audit.core.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
