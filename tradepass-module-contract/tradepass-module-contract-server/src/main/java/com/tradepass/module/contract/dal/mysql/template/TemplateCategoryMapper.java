package com.tradepass.module.contract.dal.mysql.template;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.contract.dal.dataobject.template.TemplateCategoryDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TemplateCategoryMapper extends BaseMapper<TemplateCategoryDO> {
}
