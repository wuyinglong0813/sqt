package com.tradepass.framework.fadada.core;

import java.util.List;

public interface FadadaCompanyGateway {
    String createAuthUrl(AuthCommand command);
    CompanyAccount getCompany(String clientCorpId, String openCorpId);
    CompanyAccount getCompanyByCreditCode(String creditCode);
    CompanyIdentity getIdentity(String openCorpId);
    CompanyMember getMember(String openCorpId, long memberId);
    List<Long> listMemberIds(String openCorpId, String memberName);
    CompanyMember findMemberByInternalIdentifier(String openCorpId, String internalIdentifier);
    MemberInvitation createMember(MemberCreateCommand command);
    String createMemberActivationUrl(String openCorpId, long memberId);
    String createOrganizationManageUrl(String openCorpId, String clientUserId);
    List<SealInfo> listSeals(String openCorpId);
    String createSealManageUrl(String openCorpId, String clientUserId, String redirectUrl);
    String createIdentityChangeUrl(String clientCorpId, String openCorpId, String clientUserId);

    record AuthCommand(String clientCorpId, String clientUserId, String accountName, String companyName,
                       String creditCode, List<String> authScopes, String callbackUrl,
                       String redirectMiniAppUrl) {}
    record CompanyAccount(String clientCorpId, String openCorpId, String bindingStatus,
                          String identStatus, String availableStatus, List<String> authScopes) {}
    record CompanyIdentity(String openCorpId, String identStatus, String companyName,
                           String creditCode, String legalRepName, String identMethod,
                           String submittedAt, String verifiedAt, String operatorType, String operatorId,
                           boolean operatorOpenUserIdDocumented) {
        public CompanyIdentity(String openCorpId, String identStatus, String companyName,
                               String creditCode, String legalRepName, String identMethod,
                               String submittedAt, String verifiedAt, String operatorType, String operatorId) {
            this(openCorpId, identStatus, companyName, creditCode, legalRepName, identMethod,
                    submittedAt, verifiedAt, operatorType, operatorId, false);
        }
    }
    record SealInfo(String sealId, String sealName, String categoryType, String status) {}
    record CompanyMember(Long memberId, String memberStatus, List<String> roleTypes, String fddId,
                         String internalIdentifier, List<String> accountNames) {
        public CompanyMember(Long memberId, String memberStatus, List<String> roleTypes, String fddId) {
            this(memberId, memberStatus, roleTypes, fddId, null, List.of());
        }
        @Override public String toString() { return "CompanyMember[memberStatus=" + memberStatus + ", identity=REDACTED]"; }
    }
    record MemberCreateCommand(String openCorpId, String internalIdentifier, String memberName, String accountName) {
        @Override public String toString() { return "MemberCreateCommand[REDACTED]"; }
    }
    record MemberInvitation(Long memberId, String internalIdentifier, String memberStatus, String activeEmbedUrl) {
        @Override public String toString() { return "MemberInvitation[memberStatus=" + memberStatus + ", invitation=REDACTED]"; }
    }
}
