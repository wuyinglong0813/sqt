package com.tradepass.module.contract.service.signing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable recovery for provider cancellation and the temporary actor blocks owned by this workflow. */
@Service
public class SigningCancellationIntentStore {
    public record Intent(String signTaskId, long contractId, int version, boolean unsignedOnly,
                         String reason, List<String> ownedActors) {
        public Intent { ownedActors = List.copyOf(ownedActors); }
    }

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final TransactionTemplate participating;
    private final Clock clock;

    @Autowired
    public SigningCancellationIntentStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, Clock.systemUTC());
    }

    public SigningCancellationIntentStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        participating = new TransactionTemplate(transactions);
        participating.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public Intent request(FadadaContractSignTaskDO task, String reason, boolean unsignedOnly) {
        if (task == null || task.getSignTaskId() == null || task.getSignTaskId().isBlank()
                || task.getContractId() == null || task.getContractId() <= 0 || reason == null || reason.isBlank())
            throw new IllegalArgumentException("签署撤回意图参数不完整");
        int version = task.getVersionNo() == null ? 1 : task.getVersionNo();
        if (version <= 0 || reason.length() > 256) throw new IllegalArgumentException("签署撤回意图参数无效");
        return independent.execute(t -> {
            LocalDateTime now = now();
            // Inserting first serializes concurrent first requests on the provider task's unique key.
            jdbc.update("""
                    INSERT INTO fadada_signing_cancellation_intent
                      (sign_task_id,contract_id,version_no,unsigned_only,reason,status,owned_actor_ids,requested_at,next_attempt_at)
                    VALUES (?,?,?,?,?,'PENDING','[]',?,?)
                    ON DUPLICATE KEY UPDATE sign_task_id=VALUES(sign_task_id)
                    """, task.getSignTaskId(), task.getContractId(), version, unsignedOnly, reason, now, now);
            var row = locked(task.getSignTaskId());
            Intent current = intent(row);
            if (current.contractId() != task.getContractId() || current.version() != version)
                throw new IllegalStateException("签署撤回意图与合同版本不一致");
            // A manual withdrawal is allowed to take over an unfinished/declined timeout attempt.
            // A periodic timeout request never revives a terminal intent or weakens a manual request.
            if (!unsignedOnly && ("PENDING".equals(row.get("status"))
                    || ("SKIPPED".equals(row.get("status")) && current.unsignedOnly()))) {
                jdbc.update("""
                        UPDATE fadada_signing_cancellation_intent
                        SET unsigned_only=0,reason=?,status='PENDING',next_attempt_at=?,completed_at=NULL
                        WHERE sign_task_id=?
                        """, reason, now, task.getSignTaskId());
                return new Intent(current.signTaskId(), current.contractId(), current.version(), false, reason, current.ownedActors());
            }
            return current;
        });
    }

    public List<Intent> due(LocalDateTime now, int limit) {
        if (now == null || limit <= 0 || limit > 200) throw new IllegalArgumentException("签署撤回重试参数无效");
        return jdbc.queryForList("""
                SELECT * FROM fadada_signing_cancellation_intent
                WHERE status='PENDING' AND next_attempt_at<=?
                ORDER BY next_attempt_at,requested_at,sign_task_id LIMIT ?
                """, now, limit).stream().map(this::intent).toList();
    }

    /** Refresh after the caller acquires the contract/task locks; discovery results may be stale. */
    public Intent pending(String id) {
        requireId(id);
        return independent.execute(t -> {
            var rows = jdbc.queryForList("""
                    SELECT * FROM fadada_signing_cancellation_intent WHERE sign_task_id=? AND status='PENDING'
                    """, id);
            if (rows.isEmpty()) return null;
            if (rows.size() != 1) throw new IllegalStateException("签署撤回意图不唯一");
            return intent(rows.get(0));
        });
    }

    public boolean claim(String id, LocalDateTime now) {
        requireId(id);
        Objects.requireNonNull(now, "撤回重试时间不能为空");
        return Boolean.TRUE.equals(independent.execute(t -> jdbc.update("""
                UPDATE fadada_signing_cancellation_intent SET next_attempt_at=?
                WHERE sign_task_id=? AND status='PENDING' AND next_attempt_at<=?
                """, now.plusMinutes(15), id, now) == 1));
    }

    public void rememberOwnedActor(String id, String actor) { changeOwnedActor(id, actor, true); }
    public void forgetOwnedActor(String id, String actor) { changeOwnedActor(id, actor, false); }

    private void changeOwnedActor(String id, String actor, boolean remember) {
        requireId(id);
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("签署参与方不能为空");
        independent.executeWithoutResult(t -> {
            var actors = new ArrayList<>(intent(locked(id)).ownedActors());
            boolean changed = remember ? !actors.contains(actor) && actors.add(actor) : actors.remove(actor);
            if (changed) jdbc.update("""
                    UPDATE fadada_signing_cancellation_intent SET owned_actor_ids=? WHERE sign_task_id=?
                    """, encode(actors), id);
        });
    }

    public List<String> ownedActors(String id) {
        requireId(id);
        // The surrounding contract transaction can use REPEATABLE_READ. Its snapshot must not hide
        // actor ownership committed by remember/forget in a separate durable transaction.
        return independent.execute(t -> {
            var rows = jdbc.queryForList("SELECT * FROM fadada_signing_cancellation_intent WHERE sign_task_id=?", id);
            if (rows.size() != 1) throw new IllegalStateException("签署撤回意图不存在或不唯一");
            return intent(rows.get(0)).ownedActors();
        });
    }

    /** Final state participates in the contract transaction; it must roll back with the local contract update. */
    public void complete(String id, boolean skipped) {
        requireId(id);
        participating.executeWithoutResult(t -> {
            String status = skipped ? "SKIPPED" : "DONE";
            int changed = jdbc.update("""
                    UPDATE fadada_signing_cancellation_intent SET status=?,completed_at=?
                    WHERE sign_task_id=? AND status='PENDING'
                    """, status, now(), id);
            if (changed == 0) {
                var rows = jdbc.queryForList("SELECT status FROM fadada_signing_cancellation_intent WHERE sign_task_id=?", id);
                if (rows.size() != 1 || !status.equals(rows.get(0).get("status")))
                    throw new IllegalStateException("签署撤回意图状态已变化");
            }
        });
    }

    private Map<String, Object> locked(String id) {
        var rows = jdbc.queryForList("SELECT * FROM fadada_signing_cancellation_intent WHERE sign_task_id=? FOR UPDATE", id);
        if (rows.size() != 1) throw new IllegalStateException("签署撤回意图不存在或不唯一");
        return rows.get(0);
    }

    private Intent intent(Map<String, Object> row) {
        Object flag = row.get("unsigned_only");
        boolean unsignedOnly = flag instanceof Boolean value ? value : ((Number) flag).intValue() != 0;
        return new Intent((String) row.get("sign_task_id"), ((Number) row.get("contract_id")).longValue(),
                ((Number) row.get("version_no")).intValue(), unsignedOnly, (String) row.get("reason"),
                decode((String) row.get("owned_actor_ids")));
    }

    private static List<String> decode(String value) {
        try {
            var tree = JSON.readTree(value);
            if (tree == null || !tree.isArray()) throw new IllegalStateException();
            List<String> actors = new ArrayList<>();
            for (var actor : tree) {
                if (!actor.isTextual() || actor.textValue().isBlank() || actors.contains(actor.textValue()))
                    throw new IllegalStateException();
                actors.add(actor.textValue());
            }
            return List.copyOf(actors);
        } catch (Exception e) {
            throw new IllegalStateException("签署撤回参与方恢复记录无效");
        }
    }

    private static String encode(List<String> actors) {
        try { return JSON.writeValueAsString(actors); }
        catch (Exception e) { throw new IllegalStateException("无法保存签署撤回参与方恢复记录"); }
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), BEIJING); }
    private static void requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("签署任务不能为空");
    }
}
