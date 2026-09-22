package com.tradepass.module.trade.api.approval;

import java.util.List;
import java.util.Map;
import com.tradepass.module.trade.service.approval.ApprovalService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ApprovalOperationsImpl implements ApprovalOperations {
    private final ApprovalService delegate;
    public ApprovalOperationsImpl(@Lazy ApprovalService delegate) { this.delegate = delegate; }
    @Override public List<Map<String, Object>> pendingFulfillment() { return delegate.pendingFulfillment(); }
    @Override public List<Map<String, Object>> results() { return delegate.results(); }
    @Override public Map<String, Object> summary() { return delegate.summary(); }
    @Override public void markResultRead(Long id) { delegate.markResultRead(id); }
    @Override public void recordResult(long recipientCompanyId, long sourceCompanyId,
                             String resultType, long sourceId, Long contractId,
                             String resultStatus, String title, String detail,
                             String rejectedReason) { delegate.recordResult(recipientCompanyId, sourceCompanyId, resultType, sourceId, contractId, resultStatus, title, detail, rejectedReason); }
}
