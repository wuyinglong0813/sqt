package com.tradepass.framework.fadada.core;

import com.fasc.open.api.v5_1.req.user.GetUserReq;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdkFadadaUserGatewayTest {

    @Test
    @SuppressWarnings("unchecked")
    void alreadyAuthorizedIsPreservedForReconciliationWithoutTreatingOtherErrorsAsSuccess() throws Exception {
        var properties = new FadadaProperties();
        properties.setAppId("test"); properties.setAppSecret("test");
        properties.setServerUrl("https://example.test");
        var gateway = new SdkFadadaUserGateway(properties,
                org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.UserClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "userClient", client);
        com.fasc.open.api.bean.base.BaseRes<com.fasc.open.api.v5_1.res.common.EUrlRes> response =
                org.mockito.Mockito.mock(com.fasc.open.api.bean.base.BaseRes.class);
        org.mockito.Mockito.when(client.getUserAuthUrl(org.mockito.ArgumentMatchers.any())).thenReturn(response);
        org.mockito.Mockito.when(response.getCode()).thenReturn("210002");
        var command = new FadadaUserGateway.AuthUrlCommand("local-8", null, null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.createAuthUrl(command))
                .isInstanceOfSatisfying(FadadaUserQueryException.class,
                        error -> assertThat(error.providerCode()).isEqualTo("210002"));
        org.mockito.Mockito.when(response.getCode()).thenReturn("100020");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.createAuthUrl(command))
                .isInstanceOf(com.tradepass.framework.common.exception.BusinessException.class)
                .isNotInstanceOf(FadadaUserQueryException.class);
    }

    @Test
    void diagnosticMessageRetainsReasonWithoutContactDetailsOrLongIdentifiers() {
        assertThat(SdkFadadaUserGateway.safeDiagnosticMessage(
                "clientUserId长度超过限制\ntradepass-user-1234567890123456789 13800138000 https://example.test/?token=secret user@example.test"))
                .isEqualTo("clientUserId长度超过限制 [identifier] [identifier] [url] [email]");
        assertThat(SdkFadadaUserGateway.safeDiagnosticMessage(null)).isEmpty();
    }

    @Test
    void queriesProviderWithOnlyOneUserIdentifier() {
        GetUserReq verifiedRequest = new GetUserReq();
        SdkFadadaUserGateway.applyUserLookup(
                verifiedRequest, "tradepass-user-8", "open-user-8");
        assertThat(verifiedRequest.getOpenUserId()).isEqualTo("open-user-8");
        assertThat(verifiedRequest.getClientUserId()).isNull();

        GetUserReq pendingRequest = new GetUserReq();
        SdkFadadaUserGateway.applyUserLookup(
                pendingRequest, "tradepass-user-8", null);
        assertThat(pendingRequest.getClientUserId()).isEqualTo("tradepass-user-8");
        assertThat(pendingRequest.getOpenUserId()).isNull();
    }
}
