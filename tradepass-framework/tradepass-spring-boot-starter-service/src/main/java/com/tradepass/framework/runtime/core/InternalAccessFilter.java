package com.tradepass.framework.runtime.core;

import com.tradepass.framework.common.core.AuthContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Order(Ordered.HIGHEST_PRECEDENCE)
public final class InternalAccessFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-TradePass-Internal-Key";
    public static final String USER_HEADER = "X-TradePass-User-Id";
    public static final String COMPANY_HEADER = "X-TradePass-Company-Id";
    private final String key;
    public InternalAccessFilter(String key) {
        if (key == null || key.length() < 32 || key.contains("\n") || key.contains("\r")) {
            throw new IllegalArgumentException("TRADEPASS_INTERNAL_KEY must contain at least 32 characters");
        }
        this.key = key;
    }
    public String key() { return key; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        if (request.getServletPath().startsWith("/internal")) {
            String supplied = request.getHeader(HEADER);
            if (supplied == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"服务身份校验失败\",\"data\":null}");
                return;
            }
        }
        if (request.getServletPath().startsWith("/internal/domain/")) {
            com.tradepass.framework.common.core.AuthContext.clear();
            try {
                String user = request.getHeader(USER_HEADER);
                String company = request.getHeader(COMPANY_HEADER);
                if (user != null) {
                    long userId = Long.parseLong(user);
                    Long companyId = company == null ? null : Long.valueOf(company);
                    if (userId <= 0 || (companyId != null && companyId <= 0)) throw new NumberFormatException();
                    com.tradepass.framework.common.core.AuthContext.set(userId, companyId);
                } else if (company != null) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                response.sendError(400);
                return;
            }
            try { chain.doFilter(request, response); }
            finally { com.tradepass.framework.common.core.AuthContext.clear(); }
        } else {
            // A public request cannot attach itself to an internal distributed transaction.
            chain.doFilter(new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                @Override public String getHeader(String name) {
                    if (isTransactionHeader(name)) return null;
                    return super.getHeader(name);
                }
                @Override public java.util.Enumeration<String> getHeaders(String name) {
                    if (isTransactionHeader(name)) return java.util.Collections.emptyEnumeration();
                    return super.getHeaders(name);
                }
                @Override public java.util.Enumeration<String> getHeaderNames() {
                    return java.util.Collections.enumeration(java.util.Collections.list(super.getHeaderNames())
                            .stream().filter(name -> !isTransactionHeader(name)).toList());
                }
            }, response);
        }
    }

    private static boolean isTransactionHeader(String name) {
        String normalized = name.toUpperCase(java.util.Locale.ROOT);
        return normalized.startsWith("TX_") || normalized.startsWith(".TX_") || normalized.startsWith("X-TX-");
    }
}
