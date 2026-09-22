package com.tradepass.module.identity.framework.config;

import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.service.auth.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 鉴权拦截器：校验随机会话 token → 写入请求级 AuthContext。
 * 当前操作企业优先取请求头 X-CompanyDO-Id，否则回退到用户第一家 ACTIVE 企业。
 * 未携带合法 token 时对受保护接口返回 401。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthSessionService authSessionService;
    private final CompanyMemberMapper companyMemberMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthInterceptor(AuthSessionService authSessionService, CompanyMemberMapper companyMemberMapper) {
        this.authSessionService = authSessionService;
        this.companyMemberMapper = companyMemberMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Long userId = authSessionService.resolveUserId(request.getHeader("Authorization"));
        if (userId == null) {
            writeUnauthorized(response);
            return false;
        }

        String requestedCompanyId = request.getHeader("X-CompanyDO-Id");
        Long companyId;
        if (requestedCompanyId != null && !requestedCompanyId.isBlank()) {
            companyId = resolveRequestedCompanyId(userId, requestedCompanyId);
            if (companyId == null) {
                writeForbidden(response);
                return false;
            }
        } else {
            companyId = resolveDefaultCompanyId(userId);
        }
        AuthContext.set(userId, companyId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private Long resolveRequestedCompanyId(long userId, String headerCompanyId) {
        try {
            long companyId = Long.parseLong(headerCompanyId);
            boolean exists = companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                    .eq(CompanyMemberDO::getCompanyId, companyId)
                    .eq(CompanyMemberDO::getUserId, userId)
                    .eq(CompanyMemberDO::getStatus, "ACTIVE")) > 0;
            return exists ? companyId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long resolveDefaultCompanyId(long userId) {
        try {
            List<CompanyMemberDO> members = companyMemberMapper.selectList(new LambdaQueryWrapper<CompanyMemberDO>()
                    .eq(CompanyMemberDO::getUserId, userId)
                    .eq(CompanyMemberDO::getStatus, "ACTIVE")
                    .orderByAsc(CompanyMemberDO::getCompanyId)
                    .last("LIMIT 1"));
            return members.isEmpty() ? null : members.get(0).getCompanyId();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Void> body = new ApiResponse<>(401, "未登录或登录已失效", null);
        response.getWriter().write(mapper.writeValueAsString(body));
    }

    private void writeForbidden(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Void> body = new ApiResponse<>(403, "无权访问指定企业", null);
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
