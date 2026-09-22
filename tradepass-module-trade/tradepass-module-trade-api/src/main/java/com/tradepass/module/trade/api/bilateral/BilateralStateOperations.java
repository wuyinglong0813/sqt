package com.tradepass.module.trade.api.bilateral;

import java.util.List;

/** State owned by the trade service, including the locks needed by signing. */
public interface BilateralStateOperations {
    public List<Long> approvedVoids(Long contractId, boolean lock);
    public List<Long> pendingResumes(Long contractId, boolean contractOnly, boolean lock);
    public int cancelApprovedVoids(Long contractId);
}
