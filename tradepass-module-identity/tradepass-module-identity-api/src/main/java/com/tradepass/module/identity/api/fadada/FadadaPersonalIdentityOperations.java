package com.tradepass.module.identity.api.fadada;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradepass.module.identity.api.fadada.dto.FadadaAuthUrlRespDTO;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface FadadaPersonalIdentityOperations {
    public PersonalIdentityRespDTO current();

    public PersonalIdentityRespDTO syncCurrent();

    public PersonalIdentityRespDTO requireCurrentVerified();

    public String verifiedOpenUserId(long userId);

    public FadadaAuthUrlRespDTO createAuthUrl();

    public PersonalIdentityRespDTO syncByClientUserId(String clientUserId);

    public PersonalIdentityRespDTO syncCallback(String clientUserId, JsonNode data);
}
