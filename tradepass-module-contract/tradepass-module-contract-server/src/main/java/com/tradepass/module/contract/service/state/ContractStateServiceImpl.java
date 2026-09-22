package com.tradepass.module.contract.service.state;

import com.tradepass.module.contract.api.state.ContractStateOperations;
import com.tradepass.module.contract.api.state.ContractStateOperations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractStateServiceImpl implements ContractStateService {
    private final JdbcTemplate jdbc;
    public ContractStateServiceImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public long electronicTaskCount(Long contractId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM fadada_contract_sign_task
                WHERE contract_id = ? AND sign_task_id IS NOT NULL
                """, Long.class, contractId);
        return count == null ? 0L : count;
    }
    @Transactional
    public int changeActiveStatus(Long contractId, String nextStatus) {
        if (!java.util.Set.of("VOIDED", "COMPLETED").contains(nextStatus)) throw new IllegalArgumentException("Unsupported lifecycle transition");
        return jdbc.update("""
                UPDATE trade_contract SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE'
                """, nextStatus, contractId);
    }
}
