package com.tradepass.module.identity.api.fadada;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradepass.module.identity.api.fadada.dto.FadadaAuthUrlRespDTO;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class FadadaPersonalIdentityOperationsImpl implements FadadaPersonalIdentityOperations {
    private final FadadaPersonalIdentityService delegate;
    public FadadaPersonalIdentityOperationsImpl(@Lazy FadadaPersonalIdentityService delegate) { this.delegate = delegate; }
    @Override public PersonalIdentityRespDTO current() { return delegate.current(); }
    @Override public PersonalIdentityRespDTO syncCurrent() { return delegate.syncCurrent(); }
    @Override public PersonalIdentityRespDTO requireCurrentVerified() { return delegate.requireCurrentVerified(); }
    @Override public String verifiedOpenUserId(long userId) { return delegate.verifiedOpenUserId(userId); }
    @Override public FadadaAuthUrlRespDTO createAuthUrl() { return delegate.createAuthUrl(); }
    @Override public PersonalIdentityRespDTO syncByClientUserId(String clientUserId) { return delegate.syncByClientUserId(clientUserId); }
    @Override public PersonalIdentityRespDTO syncCallback(String clientUserId, JsonNode data) { return delegate.syncCallback(clientUserId, data); }
}
