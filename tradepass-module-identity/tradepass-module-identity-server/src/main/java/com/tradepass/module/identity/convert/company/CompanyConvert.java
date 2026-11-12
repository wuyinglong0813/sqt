package com.tradepass.module.identity.convert.company;

import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface CompanyConvert {
    CompanyConvert INSTANCE = Mappers.getMapper(CompanyConvert.class);

    @Mapping(target = "confirmedReferralCount", ignore = true)
    CompanyRespDTO toDTO(CompanyDO source);
}
