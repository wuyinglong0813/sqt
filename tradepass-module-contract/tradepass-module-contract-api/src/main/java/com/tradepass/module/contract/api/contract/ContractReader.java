package com.tradepass.module.contract.api.contract;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import java.io.Serializable;

public interface ContractReader {
    public TradeContractRespDTO selectById(Serializable id);
    public TradeContractRespDTO selectByIdForUpdate(Long id);
    public long countContractsAwaitingSignature(long companyId);
}
