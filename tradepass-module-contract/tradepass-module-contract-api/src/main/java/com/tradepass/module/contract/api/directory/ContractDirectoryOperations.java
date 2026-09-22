package com.tradepass.module.contract.api.directory;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import java.util.List;

public interface ContractDirectoryOperations {
    public List<TradeContractRespDTO> contractsByIds(List<Long> ids);
    public List<TradeContractRespDTO> activePartyContracts(long companyId);
    public Long activePartyContractId(long companyId, Long contractId);
    public List<TradeContractRespDTO> partyContracts(long companyId, String counterpartyName, String status, int limit, long offset);
    public long partyContractCount(long companyId, String counterpartyName, String status);
}
