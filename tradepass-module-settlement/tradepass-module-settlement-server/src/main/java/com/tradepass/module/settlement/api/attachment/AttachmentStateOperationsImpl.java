package com.tradepass.module.settlement.api.attachment;

import com.tradepass.module.settlement.service.attachment.AttachmentStateService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AttachmentStateOperationsImpl implements AttachmentStateOperations {
    private final AttachmentStateService delegate;
    public AttachmentStateOperationsImpl(@Lazy AttachmentStateService delegate) { this.delegate = delegate; }
    @Override public AttachmentState state(Long id, boolean includeDeleted) { return delegate.state(id, includeDeleted); }
    @Override public long effectiveCount(Long contractId) { return delegate.effectiveCount(contractId); }
    @Override public long unfinishedCount(Long contractId) { return delegate.unfinishedCount(contractId); }
    @Override public int voidApproved(Long id) { return delegate.voidApproved(id); }
    @Override public long pendingConfirmationCount(long companyId) { return delegate.pendingConfirmationCount(companyId); }
}
