package com.tradepass.module.identity.convert.fadada;

import com.tradepass.module.identity.api.fadada.dto.FadadaCorpIdentityRespDTO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface FadadaCorpIdentityConvert {
    FadadaCorpIdentityConvert INSTANCE = Mappers.getMapper(FadadaCorpIdentityConvert.class);

    FadadaCorpIdentityRespDTO toDTO(FadadaCorpIdentityDO source);
}
