package com.tradepass.module.identity.convert.company;

import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface CompanyConvert {
    CompanyConvert INSTANCE = Mappers.getMapper(CompanyConvert.class);

    CompanyRespDTO toDTO(CompanyDO source);
}
