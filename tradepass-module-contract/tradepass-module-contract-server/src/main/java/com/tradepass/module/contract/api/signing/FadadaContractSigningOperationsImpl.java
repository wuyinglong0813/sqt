package com.tradepass.module.contract.api.signing;

import com.tradepass.module.contract.api.signing.dto.ContractSigningRespDTO;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.module.contract.service.signing.FadadaContractSigningService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class FadadaContractSigningOperationsImpl implements FadadaContractSigningOperations {
    private final FadadaContractSigningService delegate;
    public FadadaContractSigningOperationsImpl(@Lazy FadadaContractSigningService delegate) { this.delegate = delegate; }
    @Override public ContractSigningRespDTO current(Long contractId) { return delegate.current(contractId); }
    @Override public ServiceUrlPayload signUrl(Long contractId) { return delegate.signUrl(contractId); }
    @Override public ContractSigningRespDTO syncCurrent(Long contractId) { return delegate.syncCurrent(contractId); }
    @Override public SignedPreview signedPreview(Long contractId) { return delegate.signedPreview(contractId); }
    @Override public void syncBySignTaskId(String signTaskId) { delegate.syncBySignTaskId(signTaskId); }
    @Override public ServiceUrlPayload abolishUrl(Long contractId, String reason) { return delegate.abolishUrl(contractId, reason); }
}
