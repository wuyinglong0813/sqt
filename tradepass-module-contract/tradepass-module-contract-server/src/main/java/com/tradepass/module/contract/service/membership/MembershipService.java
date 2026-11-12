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
    private static final long REFERRAL_QUOTA = 5;
    private static final String REGISTRATION = "REGISTRATION";
    private static final String REFERRAL = "REFERRAL";

    public record Status(String companyId, String companyName, String membershipText, boolean vip, String validUntil,
                         String signingMode, boolean unlimited, Long quotaTotal, long used, long reserved, Long remaining,
                         long paidRemaining, boolean canInitiate, String reason, boolean purchaseEnabled,
                         long policyRevision, String policyUpdatedAt, String paidVipUntil, List<String> purchasePlatforms) { }
    public record Reservation(long id, String status, boolean createAllowed, String signTaskId, String sourceFileId,
                              String docId, String snapshot, String sha256, String providerInitiatorOpenCorpId) {
        public Reservation(long id, String status, boolean createAllowed, String signTaskId, String sourceFileId,
                           String docId, String snapshot, String sha256) {
            this(id, status, createAllowed, signTaskId, sourceFileId, docId, snapshot, sha256, null);
        }
    }
    private record Pool(String scope, long total, long used, long reserved, LocalDateTime expires) {
        long remaining() { return Math.max(0, total - used - reserved); }
    }
    private record Quota(long total, long used, long reserved) {
        private static final Quota EMPTY = new Quota(0, 0, 0);

        long remaining() { return Math.max(0, total - used - reserved); }

        static Quota bounded(long total, long used, long reserved) {
            long countedUsed = Math.min(total, used);
            return new Quota(total, countedUsed, Math.min(total - countedUsed, reserved));
        }
    }
    private record FreeQuotas(Quota ordinary, Quota referral, boolean usesTrial, boolean allocateTrial) { }
    private record Selection(String scope, String source, long total, String reason) { }
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
            FreeQuotas free = freeQuotas(company, subject, policy, now);
            Selection choice = select(company, subject, snapshot, now, free, false);
            boolean infinite = isWhite && "UNLIMITED".equals(white.mode());
            long total = free.ordinary().total() + free.referral().total();
            long used = free.ordinary().used() + free.referral().used();
            long reserved = free.ordinary().reserved() + free.referral().reserved();
            long remaining = free.ordinary().remaining() + free.referral().remaining();
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
        return reserve(company, contractId, version, contractSnapshot, sha256, null);
    }

    /** Freeze the provider billing initiator alongside each durable creation intent. */
    public Reservation reserve(CompanyRespDTO company, long contractId, int version, String contractSnapshot, String sha256,
                               String providerInitiatorOpenCorpId) {
        return tx.execute(t -> {
            var policy = policies.current(true);
            Reservation existing = existing(contractId, version);
            if (existing != null) {
                if ("CONSUMED".equals(existing.status())) return existing;
                if ("REFUNDED".equals(existing.status()))
                    throw new BusinessException("该版本签署已撤回并返还额度，请修改合同后重新发起");
                if (!"RELEASED".equals(existing.status()))
                    throw new BusinessException(pendingMessage(contractId, version));
            }
            String subject = subject(company);
            Instant now = clock.instant();
            FreeQuotas free = freeQuotas(company, subject, policy.policy(), now);
            Selection choice = select(company, subject, policy, now, free, true);
            if (choice.scope() == null) throw new BusinessException(choice.reason());
            jdbc.update("""
                    INSERT IGNORE INTO membership_quota_pool(scope_key,subject_key,company_id,total_amount)
                    VALUES (?,?,?,?)
                    """, choice.scope(), subject, company.getId(), choice.total());
            jdbc.update("""
                    UPDATE membership_quota_pool SET reserved_amount=reserved_amount+1,
                        total_amount=CASE
                            WHEN scope_key='REGISTRATION' THEN ?
                            WHEN scope_key='REFERRAL' THEN GREATEST(total_amount,?)
                            ELSE total_amount
                        END
                    WHERE scope_key=? AND subject_key=?
                    """, choice.total(), choice.total(), choice.scope(), subject);
            LocalDateTime createdAt = localNow();
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO membership_signing_usage(contract_id,version_no,company_id,subject_key,scope_key,
                          source,status,policy_revision,operator_id,contract_snapshot,source_sha256,created_at,
                          provider_initiator_open_corp_id)
                        VALUES (?,?,?,?,?,?,'RESERVED',?,?,?,?,?,?)
                        """, contractId, version, company.getId(), subject, choice.scope(), choice.source(),
                        policy.revision(), AuthContext.userId(), contractSnapshot, sha256, createdAt, providerInitiatorOpenCorpId);
            } else {
                jdbc.update("""
                        UPDATE membership_signing_usage SET scope_key=?,source=?,status='RESERVED',policy_revision=?,
                          operator_id=?,contract_snapshot=?,source_sha256=?,created_at=?,last_error=NULL,
                          provider_initiator_open_corp_id=?
                        WHERE contract_id=? AND version_no=? AND status='RELEASED'
                        """, choice.scope(), choice.source(), policy.revision(), AuthContext.userId(), contractSnapshot, sha256,
                        createdAt, providerInitiatorOpenCorpId, contractId, version);
            }
            Reservation result = existing(contractId, version);
            return new Reservation(result.id(), result.status(), true, null, null, null, result.snapshot(), result.sha256(),
                    result.providerInitiatorOpenCorpId());
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
        uncertain(id, null);
    }

    /**
     * Return a consumed local credit only after the caller has independently confirmed that the exact
     * provider task is stopped and both expected actors have never signed. The original task receipt
     * remains terminal so a stale callback or retry cannot consume or recreate this version again.
     */
    public void refundCancelledUnsigned(long contractId, int version, String signTaskId) {
        if (contractId <= 0 || version <= 0 || signTaskId == null || signTaskId.isBlank())
            throw new IllegalArgumentException("未签署额度返还参数不完整");
        tx.executeWithoutResult(t -> {
            policies.current(true);
            var rows = jdbc.queryForList("""
                    SELECT id,scope_key,subject_key,status,sign_task_id FROM membership_signing_usage
                    WHERE contract_id=? AND version_no=? FOR UPDATE
                    """, contractId, version);
            // Tasks created before the local membership ledger have no local credit to return.
            if (rows.isEmpty()) return;
            if (rows.size() != 1) throw new IllegalStateException("签署消费记录不唯一");
            var row = rows.get(0);
            if (!signTaskId.equals(row.get("sign_task_id")))
                throw new IllegalStateException("签署任务与消费记录不一致，不能返还额度");
            if ("REFUNDED".equals(row.get("status"))) return;
            if (!"CONSUMED".equals(row.get("status")))
                throw new IllegalStateException("签署消费状态不允许返还额度");
            int changed = jdbc.update("""
                    UPDATE membership_quota_pool SET consumed_amount=consumed_amount-1
                    WHERE scope_key=? AND subject_key=? AND consumed_amount>0
                    """, row.get("scope_key"), row.get("subject_key"));
            if (changed != 1) throw new IllegalStateException("签署消费额度记录不一致");
            int refunded = jdbc.update("""
                    UPDATE membership_signing_usage SET status='REFUNDED',last_error='未签署任务已撤回，已返还原签署额度'
                    WHERE id=? AND status='CONSUMED' AND sign_task_id=?
                    """, row.get("id"), signTaskId);
            if (refunded != 1) throw new IllegalStateException("签署消费状态已变化");
        });
    }

    /** Persist only a controlled reason independently of the surrounding signing transaction. */
    public void uncertain(long id, String reason) {
        String safeReason = com.tradepass.framework.fadada.core.FadadaProviderQuotaException.MESSAGE.equals(reason)
                ? reason : "第三方创建结果待核实";
        tx.executeWithoutResult(t -> {
            policies.current(true);
            jdbc.update("""
                    UPDATE membership_signing_usage SET status='UNCERTAIN',last_error=?
                    WHERE id=? AND status='RESERVED'
                    """, safeReason, id);
        });
    }

    /** Never echo arbitrary historical/provider messages from the database. */
    public String pendingMessage(long contractId, int version) {
        var reasons = jdbc.query("""
                SELECT last_error FROM membership_signing_usage
                WHERE contract_id=? AND version_no=? AND status IN ('RESERVED','UNCERTAIN')
                """, (rs, row) -> rs.getString(1), contractId, version);
        return reasons.stream().anyMatch(com.tradepass.framework.fadada.core.FadadaProviderQuotaException.MESSAGE::equals)
                ? com.tradepass.framework.fadada.core.FadadaProviderQuotaException.MESSAGE
                : "上次签署创建结果待核实，请稍后刷新；系统不会重复创建或扣费";
    }

    /** Used only when no create-sign-task request was issued by the gateway. */
    public void releaseBeforeTaskCreation(long id) {
        tx.executeWithoutResult(t -> {
            policies.current(true);
            var rows = jdbc.queryForList("SELECT scope_key,subject_key,status,sign_task_id FROM membership_signing_usage WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) throw new IllegalStateException("签署预占记录不存在");
            var row = rows.get(0);
            if ("RELEASED".equals(row.get("status"))) return;
            if (!"RESERVED".equals(row.get("status")) || row.get("sign_task_id") != null) {
                throw new IllegalStateException("已发起或结果待核实的签署预占不能按文件准备失败释放");
            }
            int changed = jdbc.update("""
                    UPDATE membership_quota_pool SET reserved_amount=reserved_amount-1
                    WHERE scope_key=? AND subject_key=? AND reserved_amount>0
                    """, row.get("scope_key"), row.get("subject_key"));
            if (changed != 1) throw new IllegalStateException("签署额度预占记录不一致");
            int released = jdbc.update("""
                    UPDATE membership_signing_usage SET status='RELEASED',last_error='创建签署任务前文件准备失败，已释放预占'
                    WHERE id=? AND status='RESERVED' AND sign_task_id IS NULL
                    """, id);
            if (released != 1) throw new IllegalStateException("签署预占状态已变化");
        });
    }

    public Reservation existing(long contractId, int version) {
        return jdbc.query("""
                SELECT id,status,sign_task_id,source_file_id,doc_id,contract_snapshot,source_sha256,provider_initiator_open_corp_id
                FROM membership_signing_usage WHERE contract_id=? AND version_no=?
                """, (rs, row) -> new Reservation(rs.getLong("id"), rs.getString("status"), false,
                rs.getString("sign_task_id"), rs.getString("source_file_id"), rs.getString("doc_id"),
                rs.getString("contract_snapshot"), rs.getString("source_sha256"), rs.getString("provider_initiator_open_corp_id")), contractId, version)
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

    private FreeQuotas freeQuotas(CompanyRespDTO company, String subject, MembershipPolicy policy, Instant now) {
        if (!"VERIFIED".equals(company.getCertificationStatus())) {
            return new FreeQuotas(Quota.EMPTY, Quota.EMPTY, false, false);
        }
        Pool referralPool = pool(REFERRAL, subject);
        long earned = Math.multiplyExact(company.getConfirmedReferralCount(), REFERRAL_QUOTA);
        Quota referral = referralPool == null ? new Quota(earned, 0, 0)
                : new Quota(Math.max(earned, referralPool.total()), referralPool.used(), referralPool.reserved());

        var white = policy.whitelist().get(company.getId());
        if (white != null && white.active(now)) {
            Pool special = pool("SPECIAL", subject);
            boolean unlimited = "UNLIMITED".equals(white.mode());
            Quota dedicated = new Quota(unlimited ? 0 : white.quotaTotal(),
                    special == null ? 0 : special.used(), special == null ? 0 : special.reserved());
            return new FreeQuotas(dedicated, unlimited ? Quota.EMPTY : referral, false, false);
        }

        Pool registeredPool = pool(REGISTRATION, subject);
        long registeredUsed = registeredPool == null ? 0 : registeredPool.used();
        long registeredReserved = registeredPool == null ? 0 : registeredPool.reserved();
        // Count historical trial usage toward the base and retain the actual
        // used and reserved counts when the configured allowance is reduced.
        Quota registered = jdbc.queryForObject("""
                SELECT COALESCE(SUM(consumed_amount),0), COALESCE(SUM(reserved_amount),0)
                FROM membership_quota_pool WHERE subject_key=? AND scope_key LIKE 'TRIAL:%'
                """, (rs, row) -> new Quota(policy.registrationQuota(),
                registeredUsed + rs.getLong(1), registeredReserved + rs.getLong(2)), subject);

        var trial = policy.trial();
        if (!trial.active(now)) return new FreeQuotas(registered, referral, false, false);
        Pool trialPool = pool("TRIAL:" + trial.activityId(), subject);
        if (trialPool == null && !trialAvailable(trial)) return new FreeQuotas(registered, referral, false, false);

        // A still-active promotion can provide additional capacity. Credits
        // already taken from the base cannot be claimed again from that promotion.
        Quota promotional = Quota.bounded(trialPool == null ? trial.perCompany() : trialPool.total(),
                registeredUsed + (trialPool == null ? 0 : trialPool.used()),
                registeredReserved + (trialPool == null ? 0 : trialPool.reserved()));
        if (promotional.remaining() > registered.remaining()
                || promotional.remaining() == registered.remaining() && promotional.total() > registered.total()) {
            return new FreeQuotas(promotional, referral, true, trialPool == null);
        }
        return new FreeQuotas(registered, referral, false, false);
    }

    private Selection select(CompanyRespDTO company, String subject, MembershipPolicyStore.Snapshot snapshot,
                             Instant now, FreeQuotas free, boolean allocate) {
        if (!snapshot.known()) return denied("会员策略正在同步，请稍后再试");
        var policy = snapshot.policy();
        var black = policy.blacklist().get(company.getId());
        if (black != null && black.active(now)) return denied("当前企业已暂停新发起电子签，请联系平台");
        if (!"VERIFIED".equals(company.getCertificationStatus())) return denied("请先完成企业认证");

        var white = policy.whitelist().get(company.getId());
        boolean dedicated = white != null && white.active(now);
        if (dedicated && "UNLIMITED".equals(white.mode())) {
            return new Selection("SPECIAL", "SPECIAL_UNLIMITED", 0, "本次免费无限签");
        }
        if (free.ordinary().remaining() > 0) {
            long total = free.ordinary().total();
            if (dedicated) {
                return new Selection("SPECIAL", "SPECIAL_QUOTA", total, "本次使用企业专属免费额度 1 份");
            }
            if (free.usesTrial()) {
                var trial = policy.trial();
                if (allocate && free.allocateTrial()) {
                    jdbc.update("INSERT IGNORE INTO membership_trial_budget(activity_id) VALUES (?)", trial.activityId());
                    jdbc.update("UPDATE membership_trial_budget SET allocated_amount=allocated_amount+? WHERE activity_id=?",
                            total, trial.activityId());
                }
                return new Selection("TRIAL:" + trial.activityId(), "TRIAL", total, "本次使用上线体验额度 1 份");
            }
            return new Selection(REGISTRATION, REGISTRATION, total, "本次使用企业注册免费额度 1 份");
        }
        if (free.referral().remaining() > 0) {
            return new Selection(REFERRAL, REFERRAL, free.referral().total(), "本次使用企业邀请奖励额度 1 份");
        }
        return paid(subject, dedicated ? "企业专属免费份额已用完，请联系平台补充额度"
                : "当前没有可用签署额度，请联系平台开通或补充");
    }

    private Selection paid(String subject, String fallback) {
        return paidPools(subject).stream().filter(pool -> pool.remaining() > 0).findFirst()
                .map(pool -> new Selection(pool.scope(), "PAID", pool.total(), "本次使用已购签署额度 1 份"))
                .orElseGet(() -> denied(fallback));
    }
    private Selection denied(String reason) { return new Selection(null, "", 0, reason); }
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
