package com.tradepass.module.identity.controller.internal;

import com.tradepass.framework.runtime.core.InternalContracts;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.identity.framework.config.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "tradepass.runtime.role", havingValue = "identity")
public class IdentityInternalController {
    private final AuthInterceptor auth;
    public IdentityInternalController(AuthInterceptor auth) { this.auth = auth; }
    @PostMapping("/internal/identity/resolve")
    public InternalContracts.Principal resolve(HttpServletRequest request, HttpServletResponse response) throws Exception {
        try {
            if (!auth.preHandle(request, response, this)) return null;
            return new InternalContracts.Principal(AuthContext.userId(), AuthContext.companyId());
        } finally { AuthContext.clear(); }
    }
}
