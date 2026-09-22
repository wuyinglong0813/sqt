package com.tradepass.module.trade.api.bilateral;

import java.util.List;
import com.tradepass.module.trade.service.bilateral.BilateralStateService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class BilateralStateOperationsImpl implements BilateralStateOperations {
    private final BilateralStateService delegate;
    public BilateralStateOperationsImpl(@Lazy BilateralStateService delegate) { this.delegate = delegate; }
    @Override public List<Long> approvedVoids(Long contractId, boolean lock) { return delegate.approvedVoids(contractId, lock); }
    @Override public List<Long> pendingResumes(Long contractId, boolean contractOnly, boolean lock) { return delegate.pendingResumes(contractId, contractOnly, lock); }
    @Override public int cancelApprovedVoids(Long contractId) { return delegate.cancelApprovedVoids(contractId); }
}
