package com.tradepass.module.contract.api.abolish;

import com.tradepass.module.contract.service.abolish.ContractAbolishRecoveryService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ContractAbolishRecoveryOperationsImpl implements ContractAbolishRecoveryOperations {
    private final ContractAbolishRecoveryService delegate;
    public ContractAbolishRecoveryOperationsImpl(@Lazy ContractAbolishRecoveryService delegate) { this.delegate = delegate; }
    @Override public void resumeAfterBilateralApproval(Long contractId) { delegate.resumeAfterBilateralApproval(contractId); }
}
