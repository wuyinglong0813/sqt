package com.tradepass.module.contract.service.membership;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** The singleton row is the shared policy barrier for config changes and quota reservations. */
@Service
public class MembershipPolicyStore {
    public record Snapshot(long revision, MembershipPolicy policy, String updatedAt, boolean known) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private volatile String error = "";
    private volatile Snapshot cached;
    private volatile String cachedHash;

    public MembershipPolicyStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Snapshot current(boolean lock) {
        return jdbc.queryForObject("SELECT revision, content_hash, content, updated_at FROM membership_policy_state WHERE id=1"
                + (lock ? " FOR UPDATE" : ""), (rs, row) -> {
            long revision = rs.getLong("revision");
            String hash = rs.getString("content_hash");
            Snapshot previous = cached;
            if (previous != null && previous.revision() == revision && hash.equals(cachedHash)) return previous;
            Snapshot value = new Snapshot(revision, MembershipPolicy.parse(rs.getString("content")),
                    rs.getTimestamp("updated_at").toLocalDateTime().toString().replace('T', ' '), revision > 0);
            cachedHash = hash;
            cached = value;
            return value;
        });
    }

    public void apply(String content, String source) {
        String raw = content == null ? "" : content;
        MembershipPolicy.parse(raw); // Invalid input never replaces the last valid policy.
        String hash = hash(raw);
        tx.executeWithoutResult(status -> {
            String previous = jdbc.queryForObject(
                    "SELECT content_hash FROM membership_policy_state WHERE id=1 FOR UPDATE", String.class);
            if (hash.equals(previous)) return;
            LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), MembershipPolicy.BEIJING);
            jdbc.update("INSERT INTO membership_policy_history(content_hash,content,source,applied_at) VALUES (?,?,?,?)",
                    hash, raw, source, now);
            jdbc.update("""
                    UPDATE membership_policy_state SET revision=revision+1,content_hash=?,content=?,updated_at=? WHERE id=1
                    """, hash, raw, now);
        });
        error = "";
    }

    public void failed(String message) { error = message == null ? "会员配置暂不可用" : message; }
    public String error() { return error; }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
