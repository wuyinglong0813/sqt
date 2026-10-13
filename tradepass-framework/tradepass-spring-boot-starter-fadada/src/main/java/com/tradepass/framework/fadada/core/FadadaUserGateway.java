package com.tradepass.framework.fadada.core;

import java.util.List;

public interface FadadaUserGateway {
    AuthUrlResult createAuthUrl(AuthUrlCommand command);

    UserAccountResult getUser(String clientUserId, String openUserId);

    UserIdentityResult getIdentityInfo(String openUserId);

    record AuthUrlCommand(String clientUserId,
                          String accountName,
                          String callbackUrl,
                          String redirectUrl,
                          String redirectMiniAppUrl) {
    }

    record AuthUrlResult(String authUrl) {
    }

    record UserAccountResult(String clientUserId,
                             String openUserId,
                             String bindingStatus,
                             String identStatus,
                             String availableStatus,
                             List<String> authScopes) {
    }

    record UserIdentityResult(String openUserId,
                              String identStatus,
                              String userName,
                              String identMethod,
                              String identSubmitTime,
                              String identSuccessTime,
                              String fddId) {
        public UserIdentityResult(String openUserId, String identStatus, String userName,
                                  String identMethod, String identSubmitTime, String identSuccessTime) {
            this(openUserId, identStatus, userName, identMethod, identSubmitTime, identSuccessTime, null);
        }
    }
}
