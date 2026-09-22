package com.tradepass.module.contract.api.directory;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import java.util.List;
import com.tradepass.module.contract.service.directory.ContractDirectoryService;
import com.tradepass.module.contract.convert.contract.TradeContractConvert;
import org.springframework.stereotype.Service;

@Service
public class ContractDirectoryOperationsImpl implements ContractDirectoryOperations {
    private final ContractDirectoryService delegate;
    public ContractDirectoryOperationsImpl(ContractDirectoryService delegate) { this.delegate = delegate; }
    @Override public List<TradeContractRespDTO> contractsByIds(List<Long> ids) { return delegate.contractsByIds(ids) .stream().map(TradeContractConvert.INSTANCE::toDTO).toList(); }
    @Override public List<TradeContractRespDTO> activePartyContracts(long companyId) { return delegate.activePartyContracts(companyId) .stream().map(TradeContractConvert.INSTANCE::toDTO).toList(); }
    @Override public Long activePartyContractId(long companyId, Long contractId) { return delegate.activePartyContractId(companyId, contractId); }
    @Override public List<TradeContractRespDTO> partyContracts(long companyId, String counterpartyName, String status, int limit, long offset) { return delegate.partyContracts(companyId, counterpartyName, status, limit, offset) .stream().map(TradeContractConvert.INSTANCE::toDTO).toList(); }
    @Override public long partyContractCount(long companyId, String counterpartyName, String status) { return delegate.partyContractCount(companyId, counterpartyName, status); }
}
