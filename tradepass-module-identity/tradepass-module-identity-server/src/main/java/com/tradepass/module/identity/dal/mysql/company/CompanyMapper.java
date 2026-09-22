package com.tradepass.module.identity.dal.mysql.company;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CompanyMapper extends BaseMapper<CompanyDO> {
    @Select("SELECT * FROM company WHERE id = #{id} FOR UPDATE")
    CompanyDO selectByIdForUpdate(@Param("id") Long id);
}
