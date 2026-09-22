package com.tradepass.module.trade.api.approval;

import java.util.List;
import java.util.Map;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface ApprovalOperations {
    public List<Map<String, Object>> pendingFulfillment();

    public List<Map<String, Object>> results();

    public Map<String, Object> summary();

    public void markResultRead(Long id);

    public void recordResult(long recipientCompanyId, long sourceCompanyId,
                             String resultType, long sourceId, Long contractId,
                             String resultStatus, String title, String detail,
                             String rejectedReason);
}
