package com.tradepass.framework.fadada.core;

import java.util.List;

/** Ordinary seal-use grants require a provider page confirmed by a seal manager. */
public interface FadadaSealAuthorizationGateway {
    List<MemberSealGrant> memberSealGrants(String openCorpId, long memberId);
    String memberSealGrantUrl(String openCorpId, long sealId, long memberId, String clientUserId);

    record MemberSealGrant(String sealId, String sealStatus, String grantStatus) {}
}
