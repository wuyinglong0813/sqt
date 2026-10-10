package com.tradepass.module.contract.service.membership;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
public class MembershipService {
    public record Status(String companyId, String companyName, String membershipText, boolean vip, String validUntil,
                         String signingMode, boolean unlimited, Long quotaTotal, long used, long reserved, Long remaining,
                         long paidRemaining, boolean canInitiate, String reason, boolean purchaseEnabled,
                         long policyRevision, String policyUpdatedAt, String paidVipUntil, List<String> purchasePlatforms) { }
    public record Reservation(long id, String status, boolean createAllowed, String signTaskId, String sourceFileId,
                              String docId, String snapshot, String sha256) { }
    private record Pool(String scope, long total, long used, long reserved, LocalDateTime expires) {
        long remaining() { return Math.max(0, total - used - reserved); }
    }
    private record Selection(String scope, String source, boolean unlimited, long total, long used, long reserved,
                             long remaining, String reason) { }
    private final JdbcTemplate jdbc;
    private final MembershipPolicyStore policies;
    private final TransactionTemplate tx;
    private final Clock clock;
    private com.tradepass.module.contract.service.payment.WechatPayGateway payments;
    private com.tradepass.module.identity.api.permission.AccessControlOperations access;
    @Autowired public void setPurchaseSupport(com.tradepass.module.contract.service.payment.WechatPayGateway payments,
            com.tradepass.module.identity.api.permission.AccessControlOperations access) {
        this.payments=payments; this.access=access;
    }

    @Autowired
    public MembershipService(JdbcTemplate jdbc, MembershipPolicyStore policies, PlatformTransactionManager manager) {
        this(jdbc, policies, manager, Clock.systemUTC());
    }
    public MembershipService(JdbcTemplate jdbc, MembershipPolicyStore policies, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.policies = policies; this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Status status(CompanyRespDTO company) {
        return tx.execute(t -> {
            var snapshot = policies.current(true);
            var policy = snapshot.policy();
            String subject = subject(company);
            Instant now = clock.instant();
            var white = policy.whitelist().get(company.getId());
            var black = policy.blacklist().get(company.getId());
            boolean isWhite = white != null && white.active(now);
            boolean isBlack = black != null && black.active(now);
            var trial = policy.trial();
            LocalDateTime paidVip = jdbc.query("SELECT expires_at FROM membership_paid_vip WHERE subject_key=?",
                    (rs, row) -> rs.getTimestamp(1).toLocalDateTime(), subject).stream().findFirst().orElse(null);
            boolean paid = paidVip != null && paidVip.isAfter(localNow());
            boolean vip = isWhite || trial.active(now) || paid;
            String label = isWhite ? "企业专属 VIP" : paid ? "企业 VIP" : trial.active(now) ? "上线体验中" : "企业基础版";
            String until = isWhite ? MembershipPolicy.displayEnd(white.endExclusive())
                    : paid ? MembershipPolicy.display(paidVip.atZone(MembershipPolicy.BEIJING).toInstant())
                    : trial.active(now) ? MembershipPolicy.displayEnd(trial.endExclusive()) : "";
            Selection choice = select(company, subject, snapshot, false);
            Pool special = pool("SPECIAL", subject);
            Pool trialPool = pool("TRIAL:" + trial.activityId(), subject);
            boolean infinite = isWhite && "UNLIMITED".equals(white.mode());
            long total = isWhite ? infinite ? 0 : white.quotaTotal()
                    : trial.active(now) ? trialPool == null ? trial.perCompany() : trialPool.total() : 0;
            long used = isWhite ? special == null ? 0 : special.used() : trialPool == null ? 0 : trialPool.used();
            long reserved = isWhite ? special == null ? 0 : special.reserved() : trialPool == null ? 0 : trialPool.reserved();
            long remaining = Math.max(0, total - used - reserved);
            if (!isWhite && trial.active(now) && trialPool == null && !trialAvailable(trial)) remaining = 0;
            if (!isWhite && !trial.active(now)) { used = 0; reserved = 0; }
            long paidRemaining = paidPools(subject).stream().mapToLong(Pool::remaining).sum();
            boolean verified = "VERIFIED".equals(company.getCertificationStatus());
            String reason = !verified ? "请先完成企业认证" : choice.reason();
            return new Status(company.getId().toString(), company.getName(), isBlack ? "暂停新发起签署" : label,
                    vip, until, isBlack ? "BLOCKED" : infinite ? "UNLIMITED" : isWhite ? "QUOTA" : "NORMAL",
                    infinite && !isBlack, infinite ? null : total, used, reserved, infinite ? null : remaining,
                    paidRemaining, verified && choice.scope() != null, reason,
                    verified && !isBlack && !infinite && policy.billingEnabled() && payments!=null && payments.ready()
                            && policy.products().stream().anyMatch(MembershipPolicy.Product::onSale) && access!=null
                            && (access.hasPermission(company.getId(),"member_manage") || access.hasPermission(company.getId(),"auth_manage")),
                    snapshot.revision(), snapshot.updatedAt(),
                    paid?MembershipPolicy.display(paidVip.atZone(MembershipPolicy.BEIJING).toInstant()):"",
                    payments==null?List.of():List.copyOf(payments.settings().allowedPlatforms()));
        });
    }

    /** Durable intent commits before the external side effect, even if the caller later rolls back. */
    public Reservation reserve(CompanyRespDTO company, long contractId, int version, String contractSnapshot, String sha256) {
        return tx.execute(t -> {
            var policy = policies.current(true);
            Reservation existing = existing(contractId, version);
            if (existing != null) {
                if ("CONSUMED".equals(existing.status())) return existing;
                if (!"RELEASED".equals(existing.status()))
                    throw new BusinessException("上次签署创建结果待核实，已预留额度，请联系平台核对后再试");
            }
            String subject = subject(company);
            Selection choice = select(company, subject, policy, true);
            if (choice.scope() == null) throw new BusinessException(choice.reason());
            jdbc.update("""
                    INSERT IGNORE INTO membership_quota_pool(scope_key,subject_key,company_id,total_amount)
                    VALUES (?,?,?,?)
                    """, choice.scope(), subject, company.getId(), choice.total());
            jdbc.update("""
                    UPDATE membership_quota_pool SET reserved_amount=reserved_amount+1
                    WHERE scope_key=? AND subject_key=?
                    """, choice.scope(), subject);
            LocalDateTime now = localNow();
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO membership_signing_usage(contract_id,version_no,company_id,subject_key,scope_key,
                          source,status,policy_revision,operator_id,contract_snapshot,source_sha256,created_at)
                        VALUES (?,?,?,?,?,?,'RESERVED',?,?,?,?,?)
                        """, contractId, version, company.getId(), subject, choice.scope(), choice.source(),
                        policy.revision(), AuthContext.userId(), contractSnapshot, sha256, now);
            } else {
                jdbc.update("""
                        UPDATE membership_signing_usage SET scope_key=?,source=?,status='RESERVED',policy_revision=?,
                          operator_id=?,contract_snapshot=?,source_sha256=?,created_at=?,last_error=NULL
                        WHERE contract_id=? AND version_no=? AND status='RELEASED'
                        """, choice.scope(), choice.source(), policy.revision(), AuthContext.userId(), contractSnapshot, sha256,
                        now, contractId, version);
            }
            Reservation result = existing(contractId, version);
            return new Reservation(result.id(), result.status(), true, null, null, null, result.snapshot(), result.sha256());
        });
    }

    public void confirm(long reservationId, FadadaSigningGateway.CreatedTask created) {
        tx.executeWithoutResult(t -> {
            policies.current(true);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT scope_key,subject_key,status FROM membership_signing_usage WHERE id=? FOR UPDATE", reservationId);
            if (rows.isEmpty()) throw new IllegalStateException("签署预占记录不存在");
            var row = rows.get(0);
            if ("CONSUMED".equals(row.get("status"))) return;
            if (!Set.of("RESERVED", "UNCERTAIN").contains(row.get("status")))
                throw new IllegalStateException("签署预占状态已变化");
            jdbc.update("""
                    UPDATE membership_signing_usage SET status='CONSUMED',sign_task_id=?,source_file_id=?,doc_id=?,
                      consumed_at=?,last_error=NULL WHERE id=?
                    """, created.signTaskId(), created.fileId(), created.docId(), localNow(), reservationId);
            int changed = jdbc.update("""
                    UPDATE membership_quota_pool SET reserved_amount=reserved_amount-1,consumed_amount=consumed_amount+1
                    WHERE scope_key=? AND subject_key=? AND reserved_amount>0
                    """, row.get("scope_key"), row.get("subject_key"));
            if (changed != 1) throw new IllegalStateException("签署额度预占记录不一致");
        });
    }

    public void uncertain(long id) {
        tx.executeWithoutResult(t -> {
            policies.current(true);
            jdbc.update("""
                    UPDATE membership_signing_usage SET status='UNCERTAIN',last_error='第三方创建结果待核实'
                    WHERE id=? AND status='RESERVED'
                    """, id);
        });
    }

    public Reservation existing(long contractId, int version) {
        return jdbc.query("""
                SELECT id,status,sign_task_id,source_file_id,doc_id,contract_snapshot,source_sha256
                FROM membership_signing_usage WHERE contract_id=? AND version_no=?
                """, (rs, row) -> new Reservation(rs.getLong("id"), rs.getString("status"), false,
                rs.getString("sign_task_id"), rs.getString("source_file_id"), rs.getString("doc_id"),
                rs.getString("contract_snapshot"), rs.getString("source_sha256")), contractId, version)
                .stream().findFirst().orElse(null);
    }

    public Long confirmedContract(String signTaskId) {
        return jdbc.query("SELECT contract_id FROM membership_signing_usage WHERE sign_task_id=? AND status='CONSUMED'",
                (rs, row) -> rs.getLong(1), signTaskId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> history(CompanyRespDTO company) {
        return jdbc.query("""
                SELECT id,contract_id,version_no,source,status,created_at,consumed_at FROM membership_signing_usage
                WHERE subject_key=? ORDER BY id DESC LIMIT 50
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getString("id"));
            item.put("contractId", rs.getString("contract_id"));
            item.put("version", rs.getInt("version_no"));
            item.put("source", rs.getString("source"));
            item.put("status", rs.getString("status"));
            item.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString().replace('T', ' '));
            return item;
        }, subject(company));
    }

    private Selection select(CompanyRespDTO company, String subject, MembershipPolicyStore.Snapshot snapshot, boolean allocate) {
        if (!snapshot.known()) return denied("会员策略正在同步，请稍后再试");
        var policy = snapshot.policy();
        var now = clock.instant();
        var black = policy.blacklist().get(company.getId());
        if (black != null && black.active(now)) return denied("当前企业已暂停新发起电子签，请联系平台");
        var white = policy.whitelist().get(company.getId());
        if (white != null && white.active(now)) {
            Pool pool = pool("SPECIAL", subject);
            long used = pool == null ? 0 : pool.used(), reserved = pool == null ? 0 : pool.reserved();
            if ("UNLIMITED".equals(white.mode()))
                return new Selection("SPECIAL", "SPECIAL_UNLIMITED", true, 0, used, reserved, 0, "本次免费无限签");
            long remaining = Math.max(0, white.quotaTotal() - used - reserved);
            if (remaining > 0) return new Selection("SPECIAL", "SPECIAL_QUOTA", false, white.quotaTotal(),
                    used, reserved, remaining, "本次使用企业专属免费额度 1 份");
            return paid(subject, "企业专属免费份额已用完，请联系平台补充额度");
        }
        var trial = policy.trial();
        if (trial.active(now)) {
            String scope = "TRIAL:" + trial.activityId();
            Pool pool = pool(scope, subject);
            if (pool == null && trial.perCompany() > 0 && trialAvailable(trial)) {
                if (allocate) {
                    jdbc.update("INSERT IGNORE INTO membership_trial_budget(activity_id) VALUES (?)", trial.activityId());
                    jdbc.update("UPDATE membership_trial_budget SET allocated_amount=allocated_amount+? WHERE activity_id=?",
                            trial.perCompany(), trial.activityId());
                    jdbc.update("""
                            INSERT INTO membership_quota_pool(scope_key,subject_key,company_id,total_amount) VALUES (?,?,?,?)
                            """, scope, subject, company.getId(), trial.perCompany());
                }
                return new Selection(scope, "TRIAL", false, trial.perCompany(), 0, 0, trial.perCompany(),
                        "本次使用上线体验额度 1 份");
            }
            if (pool != null && pool.remaining() > 0)
                return new Selection(scope, "TRIAL", false, pool.total(), pool.used(), pool.reserved(), pool.remaining(),
                        "本次使用上线体验额度 1 份");
            return paid(subject, pool == null ? "本期体验赠额已领完，请联系平台" : "企业体验签署额度已用完，请联系平台");
        }
        return paid(subject, "当前没有可用签署额度，请联系平台开通或补充");
    }

    private Selection paid(String subject, String fallback) {
        return paidPools(subject).stream().filter(pool -> pool.remaining() > 0).findFirst()
                .map(pool -> new Selection(pool.scope(), "PAID", false, pool.total(), pool.used(), pool.reserved(),
                        pool.remaining(), "本次使用已购签署额度 1 份")).orElseGet(() -> denied(fallback));
    }
    private Selection denied(String reason) { return new Selection(null, "", false, 0, 0, 0, 0, reason); }
    private boolean trialAvailable(MembershipPolicy.Trial trial) {
        long allocated = jdbc.query("SELECT allocated_amount FROM membership_trial_budget WHERE activity_id=?",
                (rs, row) -> rs.getLong(1), trial.activityId()).stream().findFirst().orElse(0L);
        return trial.perCompany() > 0 && allocated <= trial.budget() - trial.perCompany();
    }
    private Pool pool(String scope, String subject) {
        return jdbc.query("""
                SELECT scope_key,total_amount,consumed_amount,reserved_amount,expires_at
                FROM membership_quota_pool WHERE scope_key=? AND subject_key=?
                """, (rs, row) -> new Pool(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toLocalDateTime()), scope, subject)
                .stream().findFirst().orElse(null);
    }
    private List<Pool> paidPools(String subject) {
        return jdbc.query("""
                SELECT scope_key,total_amount,consumed_amount,reserved_amount,expires_at FROM membership_quota_pool
                WHERE subject_key=? AND scope_key LIKE 'PAID:%' AND expires_at>? ORDER BY expires_at,scope_key
                """, (rs, row) -> new Pool(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getTimestamp(5).toLocalDateTime()), subject, localNow());
    }
    private String subject(CompanyRespDTO company) {
        if (company == null || company.getCreditCode() == null || company.getCreditCode().isBlank())
            throw new BusinessException("企业主体信息不完整，请先完成企业认证");
        return company.getCreditCode().trim().toUpperCase(Locale.ROOT);
    }
    private LocalDateTime localNow() { return LocalDateTime.ofInstant(clock.instant(), MembershipPolicy.BEIJING); }
}
