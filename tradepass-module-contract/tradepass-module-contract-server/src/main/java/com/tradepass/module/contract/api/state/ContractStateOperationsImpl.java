package com.tradepass.module.contract.api.state;

import com.tradepass.module.contract.service.state.ContractStateService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ContractStateOperationsImpl implements ContractStateOperations {
    private final ContractStateService delegate;
    public ContractStateOperationsImpl(@Lazy ContractStateService delegate) { this.delegate = delegate; }
    @Override public long electronicTaskCount(Long contractId) { return delegate.electronicTaskCount(contractId); }
    @Override public int changeActiveStatus(Long contractId, String nextStatus) { return delegate.changeActiveStatus(contractId, nextStatus); }
}
