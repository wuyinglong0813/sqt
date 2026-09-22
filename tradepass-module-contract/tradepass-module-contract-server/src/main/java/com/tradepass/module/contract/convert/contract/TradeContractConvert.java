package com.tradepass.module.contract.convert.contract;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface TradeContractConvert {
    TradeContractConvert INSTANCE = Mappers.getMapper(TradeContractConvert.class);

    TradeContractRespDTO toDTO(TradeContractDO source);
}
