package com.tradepass.framework.runtime.config;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.runtime.core.InternalContracts;
import com.tradepass.framework.runtime.core.RouteOwnership;
import org.springframework.beans.factory.annotation.Qualifier;
import com.tradepass.framework.web.core.interceptor.DevModeInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.lang.reflect.Method;

@Configuration(proxyBeanMethods = false)
public class RuntimeWebConfiguration implements WebMvcConfigurer, WebMvcRegistrations {
    private final String role;
    private final ObjectProvider<HandlerInterceptor> localAuth;
    private final ObjectProvider<InternalContracts.IdentityClient> identity;
    private final DevModeInterceptor dev;
    public RuntimeWebConfiguration(@Value("${tradepass.runtime.role}") String role,
            @Qualifier("authInterceptor") ObjectProvider<HandlerInterceptor> localAuth, ObjectProvider<InternalContracts.IdentityClient> identity, DevModeInterceptor dev) {
        this.role = role; this.localAuth = localAuth; this.identity = identity; this.dev = dev;
    }

    @Override public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
        return new RequestMappingHandlerMapping() {
            @Override protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
                RequestMappingInfo mapping = super.getMappingForMethod(method, handlerType);
                if (mapping != null && handlerType.getPackageName().startsWith("com.tradepass.")
                        && !RouteOwnership.serves(role, handlerType, method)) return null;
                return mapping;
            }
        };
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(dev).addPathPatterns("/api/dev/**");
        HandlerInterceptor authentication = role.equals("identity") ? localAuth.getObject() : new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                AuthContext.clear();
                try {
                    var principal = identity.getObject().resolve(request.getHeader("Authorization"), request.getHeader("X-Company-Id"));
                    if (principal == null) throw new IllegalStateException("Missing identity response");
                    AuthContext.set(principal.userId(), principal.companyId());
                    return true;
                } catch (feign.FeignException error) {
                    int status = error.status();
                    boolean denied = status == 401 || status == 403;
                    response.setStatus(denied ? status : 503);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(denied ? error.contentUTF8()
                            : "{\"code\":503,\"message\":\"身份服务暂时不可用，请稍后重试\",\"data\":null}");
                    return false;
                }
            }
            @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
                AuthContext.clear();
            }
        };
        registry.addInterceptor(authentication).addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/wechat-login", "/api/company-certifications/provider-callback",
                        "/api/fadada/callback", "/api/dev/**");
    }

    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS").allowedHeaders("*");
    }
}
