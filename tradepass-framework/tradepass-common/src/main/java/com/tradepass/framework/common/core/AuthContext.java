package com.tradepass.framework.common.core;

import com.tradepass.framework.common.exception.BusinessException;

/**
 * 请求级当前用户上下文（ThreadLocal）。
 * 取代旧的全局 currentUserId/currentCompanyId 单例状态，避免多用户并发串号。
 * 由 AuthInterceptor 在每个请求开始时写入、结束时清理。
 */
public final class AuthContext {

    public record Principal(long userId, Long companyId) {
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(long userId, Long companyId) {
        HOLDER.set(new Principal(userId, companyId));
    }

    public static Principal get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static boolean isLoggedIn() {
        return HOLDER.get() != null;
    }

    /** 当前登录用户 ID，未登录抛业务异常 */
    public static long userId() {
        Principal p = HOLDER.get();
        if (p == null) {
            throw new BusinessException("未登录或登录已失效");
        }
        return p.userId();
    }

    /** 当前操作企业 ID，可能为空（用户尚未加入任何企业） */
    public static Long companyId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.companyId();
    }

    /** 当前操作企业 ID，要求必须存在 */
    public static long requireCompanyId() {
        Long cid = companyId();
        if (cid == null) {
            throw new BusinessException("尚未选择企业");
        }
        return cid;
    }
}
