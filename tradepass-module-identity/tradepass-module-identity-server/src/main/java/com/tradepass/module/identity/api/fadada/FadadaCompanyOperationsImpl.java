package com.tradepass.module.identity.api.fadada;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradepass.module.identity.api.fadada.dto.FadadaCompanyIdentityRespDTO;
import com.tradepass.module.identity.api.fadada.dto.LegalRepresentativeRespDTO;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.module.identity.api.fadada.dto.FadadaCorpIdentityRespDTO;
import com.tradepass.module.identity.service.fadada.FadadaCompanyService;
import com.tradepass.module.identity.convert.fadada.FadadaCorpIdentityConvert;
import org.springframework.stereotype.Service;

@Service
public class FadadaCompanyOperationsImpl implements FadadaCompanyOperations {
    private final FadadaCompanyService delegate;
    public FadadaCompanyOperationsImpl(FadadaCompanyService delegate) { this.delegate = delegate; }
    @Override public FadadaCompanyIdentityRespDTO current(long companyId) { return delegate.current(companyId); }
    @Override public ServiceUrlPayload createAuthUrl(long companyId) { return delegate.createAuthUrl(companyId); }
    @Override public FadadaCompanyIdentityRespDTO syncCurrent(long companyId) { return delegate.syncCurrent(companyId); }
    @Override public ServiceUrlPayload createLegalRepresentativeUrl(long companyId) { return delegate.createLegalRepresentativeUrl(companyId); }
    @Override public LegalRepresentativeRespDTO syncLegalRepresentative(long companyId) { return delegate.syncLegalRepresentative(companyId); }
    @Override public FadadaCompanyIdentityRespDTO sync(long companyId) { return delegate.sync(companyId); }
    @Override public FadadaCompanyIdentityRespDTO syncByClientCorpId(String clientCorpId) { return delegate.syncByClientCorpId(clientCorpId); }
    @Override public FadadaCompanyIdentityRespDTO syncByOpenCorpId(String openCorpId) { return delegate.syncByOpenCorpId(openCorpId); }
    @Override public FadadaCompanyIdentityRespDTO syncCallback(String clientCorpId, String openCorpId, JsonNode data) { return delegate.syncCallback(clientCorpId, openCorpId, data); }
    @Override public ServiceUrlPayload createSealManageUrl(long companyId) { return delegate.createSealManageUrl(companyId); }
    @Override public FadadaCorpIdentityRespDTO requireVerified(long companyId) { return FadadaCorpIdentityConvert.INSTANCE.toDTO(delegate.requireVerified(companyId)); }
    @Override public String enabledSealId(long companyId) { return delegate.enabledSealId(companyId); }
}
