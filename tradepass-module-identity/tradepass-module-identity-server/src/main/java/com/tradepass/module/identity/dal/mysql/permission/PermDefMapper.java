package com.tradepass.module.identity.dal.mysql.permission;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.permission.PermDefDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PermDefMapper extends BaseMapper<PermDefDO> {
}
