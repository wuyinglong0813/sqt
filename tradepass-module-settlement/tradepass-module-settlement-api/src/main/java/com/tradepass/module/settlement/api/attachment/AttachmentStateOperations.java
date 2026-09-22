package com.tradepass.module.settlement.api.attachment;

public interface AttachmentStateOperations {
    public AttachmentState state(Long id, boolean includeDeleted);
    public long effectiveCount(Long contractId);
    public long unfinishedCount(Long contractId);
    public int voidApproved(Long id);
    public long pendingConfirmationCount(long companyId);
    public record AttachmentState(Long contractId, String status, String category, String originalName) { }
}
