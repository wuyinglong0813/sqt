package com.tradepass.module.settlement.api.attachment;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import com.tradepass.module.settlement.service.attachment.AttachmentApprovalService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AttachmentApprovalOperationsImpl implements AttachmentApprovalOperations {
    private final AttachmentApprovalService delegate;
    public AttachmentApprovalOperationsImpl(@Lazy AttachmentApprovalService delegate) { this.delegate = delegate; }
    @Override public List<PendingAttachment> pendingAttachments(long companyId) { return delegate.pendingAttachments(companyId); }
}
