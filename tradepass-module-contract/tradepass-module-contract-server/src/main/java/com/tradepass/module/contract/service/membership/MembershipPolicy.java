package com.tradepass.module.contract.service.membership;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

/** Immutable, all-or-nothing operational policy. Human-readable times are always Beijing time. */
public record MembershipPolicy(Trial trial, boolean billingEnabled,
                               Map<Long, Rule> blacklist, Map<Long, Rule> whitelist) {
    public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    public record Trial(boolean enabled, String activityId, Instant endExclusive, long perCompany, long budget) {
        public boolean active(Instant now) { return enabled && now.isBefore(endExclusive); }
    }
    public record Rule(String mode, Long quotaTotal, Instant endExclusive) {
        public boolean active(Instant now) { return endExclusive == null || now.isBefore(endExclusive); }
    }

    public static MembershipPolicy empty() {
        return new MembershipPolicy(new Trial(false, "", Instant.EPOCH, 0, 0), false, Map.of(), Map.of());
    }

    public static MembershipPolicy parse(String content) {
        if (content == null || content.isBlank()) return empty();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(262144);
        Object raw = new Yaml(new SafeConstructor(options)).load(content);
        Map<?, ?> root = map(raw);
        keys(root, "tradepass");
        Map<?, ?> parent = map(root.get("tradepass"));
        keys(parent, "membership");
        if (!root.isEmpty() && !parent.containsKey("membership"))
            throw new IllegalArgumentException("缺少 tradepass.membership 配置");
        Map<?, ?> config = map(parent.get("membership"));
        keys(config, "trial", "billing", "blacklist", "whitelist");
        if (config.isEmpty()) return empty();
        Map<?, ?> trial = map(config.get("trial"));
        keys(trial, "enabled", "activity-id", "end-at", "sign-quota-per-company", "total-sign-quota");
        boolean enabled = bool(trial, "enabled", false);
        String activity = string(trial.get("activity-id"));
        Instant end = end(trial.get("end-at"));
        long each = number(trial.get("sign-quota-per-company"), 5);
        long budget = number(trial.get("total-sign-quota"), 500);
        if (enabled && (end == null || !activity.matches("[A-Za-z0-9_-]{1,48}")))
            throw new IllegalArgumentException("体验启用时必须填写合法 activity-id 和北京时间 end-at");
        Map<?, ?> billing = map(config.get("billing"));
        keys(billing, "enabled");
        return new MembershipPolicy(new Trial(enabled, activity, end == null ? Instant.EPOCH : end, each, budget),
                bool(billing, "enabled", false), rules(config.get("blacklist"), true),
                rules(config.get("whitelist"), false));
    }

    private static Map<Long, Rule> rules(Object raw, boolean deny) {
        if (raw == null) return Map.of();
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("企业名单必须是 YAML 列表");
        if (list.size() > 2000) throw new IllegalArgumentException("企业名单最多 2000 项");
        Map<Long, Rule> rules = new LinkedHashMap<>();
        for (Object value : list) {
            Map<?, ?> entry = map(value);
            if (deny) keys(entry, "company-id", "company-name", "valid-until", "reason");
            else keys(entry, "company-id", "company-name", "valid-until", "reason", "mode", "quota-total");
            String id = string(entry.get("company-id"));
            if (!id.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("企业 ID 必须为正整数");
            long companyId;
            try { companyId = Long.parseLong(id); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("企业 ID 超出范围"); }
            String mode = deny ? "BLOCKED" : string(entry.get("mode")).toUpperCase(Locale.ROOT);
            Long quota = null;
            if (!deny) {
                if (!Set.of("UNLIMITED", "QUOTA").contains(mode)) throw new IllegalArgumentException("白名单 mode 必须为 UNLIMITED 或 QUOTA");
                if ("QUOTA".equals(mode)) {
                    if (!entry.containsKey("quota-total")) throw new IllegalArgumentException("QUOTA 必须填写 quota-total");
                    quota = number(entry.get("quota-total"), 0);
                } else if (entry.containsKey("quota-total"))
                    throw new IllegalArgumentException("UNLIMITED 不应填写 quota-total");
            }
            if (rules.put(companyId, new Rule(mode, quota, end(entry.get("valid-until")))) != null)
                throw new IllegalArgumentException("同一名单中企业 ID 不可重复");
        }
        return Collections.unmodifiableMap(rules);
    }

    /** End includes the whole written second, so 23:59:59 includes the end of that day. */
    private static Instant end(Object value) {
        String text = string(value);
        if (text.isBlank()) return null;
        try { return LocalDateTime.parse(text, FORMAT).atZone(BEIJING).toInstant().plusSeconds(1); }
        catch (RuntimeException localError) {
            try { return OffsetDateTime.parse(text).toInstant().plusSeconds(1); }
            catch (RuntimeException e) { throw new IllegalArgumentException("时间请填写北京时间 yyyy-MM-dd HH:mm:ss"); }
        }
    }

    public static String displayEnd(Instant exclusive) {
        return exclusive == null ? "" : FORMAT.format(exclusive.minusSeconds(1).atZone(BEIJING));
    }
    public static String display(Instant instant) { return FORMAT.format(instant.atZone(BEIJING)); }
    private static String string(Object value) { return value == null ? "" : value.toString().trim(); }
    private static Map<?, ?> map(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> value)) throw new IllegalArgumentException("会员配置必须为 YAML 对象");
        return value;
    }
    private static void keys(Map<?, ?> map, String... names) {
        Set<String> allowed = Set.of(names);
        for (Object key : map.keySet()) if (!allowed.contains(key.toString()))
            throw new IllegalArgumentException("未知会员配置字段：" + key);
    }
    private static boolean bool(Map<?, ?> map, String name, boolean fallback) {
        Object value = map.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(name + " 必须为 true/false");
        return result;
    }
    private static long number(Object value, long fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0
                || ((Number) value).longValue() > 1_000_000_000L)
            throw new IllegalArgumentException("份额必须为 0 至 1000000000 的整数");
        return ((Number) value).longValue();
    }
}
