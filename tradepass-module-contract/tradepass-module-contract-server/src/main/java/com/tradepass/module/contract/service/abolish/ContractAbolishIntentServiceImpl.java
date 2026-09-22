package com.tradepass.module.contract.service.abolish;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** The calling transaction holds the contract lock; this table has no parent-row foreign keys. */
@Service
public class ContractAbolishIntentServiceImpl implements ContractAbolishIntentService {
    private final JdbcTemplate jdbc;

    public ContractAbolishIntentServiceImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String begin(FadadaContractSignTaskDO task) {
        if (!pendingRows(task).isEmpty()) throw new BusinessException("作废任务创建结果尚未确认，请先刷新签署状态");
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO fadada_abolish_creation_intent
                  (id, contract_id, version_no, original_sign_task_id, previous_abolish_task_id)
                VALUES (?, ?, ?, ?, ?)
                """, id, task.getContractId(), version(task), task.getSignTaskId(), task.getAbolishedSignTaskId());
        return id;
    }

    // Fresh independent read avoids the caller's RR snapshot and never leaves a gap lock
    // that could block begin() in its separate transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<PendingIntent> pending(FadadaContractSignTaskDO task) { return pendingRows(task); }

    private List<PendingIntent> pendingRows(FadadaContractSignTaskDO task) {
        return jdbc.query("""
                SELECT id, previous_abolish_task_id FROM fadada_abolish_creation_intent
                WHERE contract_id = ? AND version_no = ? AND original_sign_task_id = ? AND status = 'UNCONFIRMED'
                """, (rs, row) -> new PendingIntent(rs.getString("id"), rs.getString("previous_abolish_task_id")),
                task.getContractId(), version(task), task.getSignTaskId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(String intentId, String childId) {
        if (jdbc.update("""
                UPDATE fadada_abolish_creation_intent SET status = 'CONFIRMED',
                    abolished_sign_task_id = ?, confirmed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'UNCONFIRMED'
                """, childId, intentId) != 1) throw new BusinessException("作废任务记录状态已变化，请重新同步");
    }

    private int version(FadadaContractSignTaskDO task) { return task.getVersionNo() == null ? 1 : task.getVersionNo(); }
    
}
