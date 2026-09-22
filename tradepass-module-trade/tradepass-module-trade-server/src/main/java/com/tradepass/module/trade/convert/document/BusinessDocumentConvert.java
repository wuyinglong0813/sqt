package com.tradepass.module.trade.convert.document;

import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface BusinessDocumentConvert {
    BusinessDocumentConvert INSTANCE = Mappers.getMapper(BusinessDocumentConvert.class);

    BusinessDocumentRespDTO toDTO(BusinessDocumentDO source);
}
