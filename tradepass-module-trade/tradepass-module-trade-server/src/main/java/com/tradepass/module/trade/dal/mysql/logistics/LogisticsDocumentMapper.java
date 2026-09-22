package com.tradepass.module.trade.dal.mysql.logistics;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.trade.dal.dataobject.logistics.LogisticsDocumentDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LogisticsDocumentMapper extends BaseMapper<LogisticsDocumentDO> {
}
