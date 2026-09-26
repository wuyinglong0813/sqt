package com.tradepass.framework.fadada.core;

import com.tradepass.framework.fadada.config.FadadaProperties;

import com.fasc.open.api.v5_1.req.corp.GetCorpReq;
import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SdkFadadaCompanyGatewayTest {
    @Test
    @SuppressWarnings("unchecked")
    void creditCodeLookupSendsOnlyCreditCodeAndPreservesProviderErrorCodes() throws Exception {
        var properties = new FadadaProperties();
        properties.setAppId("test"); properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var tokens = org.mockito.Mockito.mock(FadadaAccessTokenProvider.class);
        var gateway = new SdkFadadaCompanyGateway(properties, tokens);
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.CorpClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "corpClient", client);
        var detail = new com.fasc.open.api.v5_1.res.corp.CorpRes();
        detail.setOpenCorpId("open-3"); detail.setClientCorpId("local-3");
        com.fasc.open.api.bean.base.BaseRes<com.fasc.open.api.v5_1.res.corp.CorpRes> response =
                org.mockito.Mockito.mock(com.fasc.open.api.bean.base.BaseRes.class);
        org.mockito.Mockito.when(response.isSuccess()).thenReturn(true);
        org.mockito.Mockito.when(response.getData()).thenReturn(detail);
        org.mockito.Mockito.when(client.get(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            GetCorpReq request = call.getArgument(0);
            assertThat(request.getCorpIdentNo()).isEqualTo("TEST-CREDIT");
            assertThat(request.getClientCorpId()).isNull();
            assertThat(request.getOpenCorpId()).isNull();
            return response;
        });
        assertThat(gateway.getCompanyByCreditCode("TEST-CREDIT").openCorpId()).isEqualTo("open-3");
        org.mockito.Mockito.when(response.isSuccess()).thenReturn(false);
        for (String code : java.util.List.of("210032", "100020")) {
            org.mockito.Mockito.when(response.getCode()).thenReturn(code);
            assertThatThrownBy(() -> gateway.getCompanyByCreditCode("TEST-CREDIT"))
                    .isInstanceOfSatisfying(FadadaCompanyQueryException.class,
                            error -> assertThat(error.providerCode()).isEqualTo(code));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void legalVerificationUrlBindsTheCurrentPersonalAccountToTheVerifiedEnterprise() throws Exception {
        var properties = new com.tradepass.framework.fadada.config.FadadaProperties();
        properties.setAppId("test-app"); properties.setAppSecret("test-secret");
        properties.setServerUrl("https://example.test");
        var tokens = org.mockito.Mockito.mock(FadadaAccessTokenProvider.class);
        org.mockito.Mockito.when(tokens.get()).thenReturn("test-token");
        var gateway = new SdkFadadaCompanyGateway(properties, tokens);
        var corpClient = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.CorpClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "corpClient", corpClient);
        var detail = new com.fasc.open.api.v5_1.res.corp.GetChangeCorpIdentityInfoUrlRes();
        detail.setChangeIdentityInfoUrl("https://example.test/legal");
        com.fasc.open.api.bean.base.BaseRes<com.fasc.open.api.v5_1.res.corp.GetChangeCorpIdentityInfoUrlRes> response =
                org.mockito.Mockito.mock(com.fasc.open.api.bean.base.BaseRes.class);
        org.mockito.Mockito.when(response.isSuccess()).thenReturn(true);
        org.mockito.Mockito.when(response.getData()).thenReturn(detail);
        org.mockito.Mockito.when(corpClient.getChangeCorpIdentityInfoUrl(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var request = (com.fasc.open.api.v5_1.req.corp.GetChangeCorpIdentityInfoUrlReq) call.getArgument(0);
            assertThat(request.getOpenCorpId()).isEqualTo("open-3");
            assertThat(request.getClientCorpId()).isNull();
            assertThat(request.getClientUserId()).isEqualTo("tradepass-user-9");
            assertThat(request.getAccessToken()).isEqualTo("test-token");
            return response;
        });
        assertThat(gateway.createIdentityChangeUrl("local-3", "open-3", "tradepass-user-9"))
                .isEqualTo("https://example.test/legal");
    }

    @Test
    void preservesProviderOperatorIdentityInsteadOfInferringItFromCompanyCertification() {
        var response = new com.fasc.open.api.v5_1.res.corp.CorpIdentityInfoRes();
        response.setOpenCorpId("corp-3");
        response.setCorpIdentStatus("identified");
        response.setOperatorType("deputy_auth");
        response.setOperatorId("open-user-7");
        var result = SdkFadadaCompanyGateway.toCompanyIdentity(response);
        assertThat(result.operatorType()).isEqualTo("deputy_auth");
        assertThat(result.operatorId()).isEqualTo("open-user-7");
    }
    @Test
    void sendsExactlyOneCompanyIdentifier() {
        GetCorpReq known = new GetCorpReq();
        SdkFadadaCompanyGateway.applyCompanyLookup(known, "local-1", "open-1");
        assertThat(known.getOpenCorpId()).isEqualTo("open-1");
        assertThat(known.getClientCorpId()).isNull();
        GetCorpReq pending = new GetCorpReq();
        SdkFadadaCompanyGateway.applyCompanyLookup(pending, "local-1", " ");
        assertThat(pending.getClientCorpId()).isEqualTo("local-1");
        assertThat(pending.getOpenCorpId()).isNull();
        assertThatThrownBy(() -> SdkFadadaCompanyGateway.applyCompanyLookup(new GetCorpReq(), null, null))
                .isInstanceOf(BusinessException.class);
    }
}
