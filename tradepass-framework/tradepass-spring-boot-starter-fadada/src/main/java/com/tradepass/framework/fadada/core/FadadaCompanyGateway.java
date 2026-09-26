package com.tradepass.framework.fadada.core;

import java.util.List;

public interface FadadaCompanyGateway {
    String createAuthUrl(AuthCommand command);
    CompanyAccount getCompany(String clientCorpId, String openCorpId);
    CompanyAccount getCompanyByCreditCode(String creditCode);
    CompanyIdentity getIdentity(String openCorpId);
    List<SealInfo> listSeals(String openCorpId);
    String createSealManageUrl(String openCorpId, String clientUserId, String redirectUrl);
    String createIdentityChangeUrl(String clientCorpId, String openCorpId, String clientUserId);

    record AuthCommand(String clientCorpId, String clientUserId, String companyName,
                       String creditCode, List<String> authScopes, String callbackUrl,
                       String redirectMiniAppUrl) {}
    record CompanyAccount(String clientCorpId, String openCorpId, String bindingStatus,
                          String identStatus, String availableStatus, List<String> authScopes) {}
    record CompanyIdentity(String openCorpId, String identStatus, String companyName,
                           String creditCode, String legalRepName, String identMethod,
                           String submittedAt, String verifiedAt, String operatorType, String operatorId) {}
    record SealInfo(String sealId, String sealName, String categoryType, String status) {}
}
