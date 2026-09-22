package com.tradepass.module.trade.service.bilateral;

import com.tradepass.module.trade.api.bilateral.BilateralStateOperations;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface BilateralStateService {
    List<Long> approvedVoids(Long contractId, boolean lock);
    List<Long> pendingResumes(Long contractId, boolean contractOnly, boolean lock);
    int cancelApprovedVoids(Long contractId);
}
