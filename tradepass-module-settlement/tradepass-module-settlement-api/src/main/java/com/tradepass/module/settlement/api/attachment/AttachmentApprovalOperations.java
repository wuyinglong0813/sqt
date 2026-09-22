package com.tradepass.module.settlement.api.attachment;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

public interface AttachmentApprovalOperations {
    public record PendingAttachment(Map<String, Object> view, LocalDateTime createdAt) { }
    public List<PendingAttachment> pendingAttachments(long companyId);
}
