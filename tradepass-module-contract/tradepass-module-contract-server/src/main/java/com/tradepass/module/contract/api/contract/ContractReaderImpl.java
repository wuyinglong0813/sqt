package com.tradepass.module.contract.api.contract;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.convert.contract.TradeContractConvert;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import org.springframework.stereotype.Service;

@Service
public class ContractReaderImpl implements ContractReader {
    private final TradeContractMapper mapper;
    public ContractReaderImpl(TradeContractMapper mapper) { this.mapper = mapper; }
    @Override public TradeContractRespDTO selectById(java.io.Serializable id) { return TradeContractConvert.INSTANCE.toDTO(mapper.selectById(id)); }
    @Override public TradeContractRespDTO selectByIdForUpdate(Long id) { return TradeContractConvert.INSTANCE.toDTO(mapper.selectByIdForUpdate(id)); }
    @Override public long countContractsAwaitingSignature(long companyId) { return mapper.countContractsAwaitingSignature(companyId); }
}
