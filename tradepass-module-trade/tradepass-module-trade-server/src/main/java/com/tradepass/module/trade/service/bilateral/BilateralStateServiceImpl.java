package com.tradepass.module.trade.service.bilateral;

import com.tradepass.module.trade.api.bilateral.BilateralStateOperations;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class BilateralStateServiceImpl implements BilateralStateService {
    private final JdbcTemplate jdbc;
    public BilateralStateServiceImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public List<Long> approvedVoids(Long contractId, boolean lock) {
        if (lock) jdbc.update("""
                UPDATE bilateral_action_request SET created_at = created_at
                WHERE contract_id = ? AND biz_type = 'CONTRACT' AND action_type = 'VOID' AND status = 'APPROVED'
                """, contractId);
        return jdbc.queryForList("""
                SELECT id FROM bilateral_action_request
                WHERE contract_id = ? AND biz_type = 'CONTRACT' AND action_type = 'VOID'
                  AND status = 'APPROVED'
                """ + (lock ? " FOR UPDATE" : ""), Long.class, contractId);
    }
    @Transactional
    public List<Long> pendingResumes(Long contractId, boolean contractOnly, boolean lock) {
        if (lock) jdbc.update("""
                UPDATE bilateral_action_request SET created_at = created_at
                WHERE contract_id = ? AND action_type = 'RESUME' AND status = 'PENDING'
                """ + (contractOnly ? " AND biz_type = 'CONTRACT'" : ""), contractId);
        return jdbc.queryForList("""
                SELECT id FROM bilateral_action_request
                WHERE contract_id = ? AND action_type = 'RESUME' AND status = 'PENDING'
                """ + (contractOnly ? " AND biz_type = 'CONTRACT'" : "") + (lock ? " FOR UPDATE" : ""), Long.class, contractId);
    }
    @Transactional
    public int cancelApprovedVoids(Long contractId) {
        return jdbc.update("""
                UPDATE bilateral_action_request SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP
                WHERE contract_id = ? AND biz_type = 'CONTRACT' AND action_type = 'VOID'
                  AND status = 'APPROVED'
                """, contractId);
    }
}
