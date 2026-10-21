package com.tradepass.module.contract.api.contract;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import java.io.Serializable;

public interface ContractReader {
    /** Snapshot read for display and authorization; does not wait for a contract writer's row lock. */
    public TradeContractRespDTO selectById(Serializable id);
    /** Current, locked read for a transaction that is about to change contract-related state. */
    public TradeContractRespDTO selectByIdForUpdate(Long id);
    public long countContractsAwaitingSignature(long companyId);
}
