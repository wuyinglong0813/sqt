package com.tradepass.framework.web.core.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.pojo.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 生产环境拦截 /api/dev/**，返回 404，避免开发工具接口暴露。
 */
@Component
public class DevModeInterceptor implements HandlerInterceptor {

    private final boolean devEnabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public DevModeInterceptor(@Value("${tradepass.dev.enabled:true}") boolean devEnabled) {
        this.devEnabled = devEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (devEnabled) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/dev")) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json;charset=UTF-8");
            ApiResponse<Void> body = new ApiResponse<>(404, "Not Found", null);
            response.getWriter().write(mapper.writeValueAsString(body));
            return false;
        }
        return true;
    }
}
