package com.tradepass.module.identity.api.fadada;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradepass.module.identity.api.fadada.dto.FadadaCompanyIdentityRespDTO;
import com.tradepass.module.identity.api.fadada.dto.LegalRepresentativeRespDTO;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.module.identity.api.fadada.dto.FadadaCorpIdentityRespDTO;
import com.tradepass.module.identity.service.fadada.FadadaCompanyService;
import com.tradepass.module.identity.service.fadada.FadadaSigningMemberService;
import com.tradepass.module.identity.service.fadada.FadadaSigningAuthorizationService;
import com.tradepass.module.identity.convert.fadada.FadadaCorpIdentityConvert;
import org.springframework.stereotype.Service;

@Service
public class FadadaCompanyOperationsImpl implements FadadaCompanyOperations {
    private final FadadaCompanyService delegate;
    private final FadadaSigningMemberService signingMembers;
    private final FadadaSigningAuthorizationService signingAuthorization;
    public FadadaCompanyOperationsImpl(FadadaCompanyService delegate, FadadaSigningMemberService signingMembers,
                                      FadadaSigningAuthorizationService signingAuthorization) {
        this.delegate = delegate; this.signingMembers = signingMembers; this.signingAuthorization = signingAuthorization;
    }
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
    @Override public void requireCurrentSigningMember(long companyId) { signingMembers.requireCurrentSigningMember(companyId); }
    @Override public void requireCurrentSigningAuthorization(long companyId) { signingAuthorization.requireCurrentSigningAuthorization(companyId); }
    @Override public ServiceUrlPayload memberSealGrantUrl(long companyId, long userId, String sealId) { return signingAuthorization.memberSealGrantUrl(companyId, userId, sealId); }
}
