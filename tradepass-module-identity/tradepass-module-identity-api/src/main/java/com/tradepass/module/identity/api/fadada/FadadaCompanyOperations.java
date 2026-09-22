package com.tradepass.module.identity.api.fadada;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradepass.module.identity.api.fadada.dto.FadadaCompanyIdentityRespDTO;
import com.tradepass.module.identity.api.fadada.dto.LegalRepresentativeRespDTO;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.module.identity.api.fadada.dto.FadadaCorpIdentityRespDTO;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface FadadaCompanyOperations {
    public FadadaCompanyIdentityRespDTO current(long companyId);

    public ServiceUrlPayload createAuthUrl(long companyId);

    public FadadaCompanyIdentityRespDTO syncCurrent(long companyId);

    public ServiceUrlPayload createLegalRepresentativeUrl(long companyId);

    public LegalRepresentativeRespDTO syncLegalRepresentative(long companyId);

    public FadadaCompanyIdentityRespDTO sync(long companyId);

    public FadadaCompanyIdentityRespDTO syncByClientCorpId(String clientCorpId);

    public FadadaCompanyIdentityRespDTO syncByOpenCorpId(String openCorpId);

    public FadadaCompanyIdentityRespDTO syncCallback(String clientCorpId, String openCorpId, JsonNode data);

    public ServiceUrlPayload createSealManageUrl(long companyId);

    public FadadaCorpIdentityRespDTO requireVerified(long companyId);

    public String enabledSealId(long companyId);
}
