package com.tradepass.framework.fadada.core;

import com.tradepass.framework.fadada.config.FadadaProperties;

import com.fasc.open.api.v5_1.req.corp.GetCorpReq;
import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SdkFadadaCompanyGatewayTest {
    @Test
    void readsEverySealPageAndOnlyReturnsThePrimaryCompanysSeals() throws Exception {
        var properties = new FadadaProperties(); properties.setAppId("test");
        properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.SealClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "sealClient", client);
        org.mockito.Mockito.when(client.getSealInfoListNew(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var request = (com.fasc.open.api.v5_1.req.seal.GetSealInfoListReq) call.getArgument(0);
            assertThat(request.getListPageSize()).isEqualTo(100);
            var page = new com.fasc.open.api.v5_1.res.seal.GetSealInfoListRes();
            page.setListPageNo(request.getListPageNo()); page.setListPageCount(2);
            var own = new com.fasc.open.api.v5_1.res.seal.GetSealInfoListRes.SealBaseInfo();
            own.setSealId((long) request.getListPageNo()); own.setEntityType("primary"); own.setSealStatus("enable");
            var subsidiary = new com.fasc.open.api.v5_1.res.seal.GetSealInfoListRes.SealBaseInfo();
            subsidiary.setSealId(99L); subsidiary.setEntityType("subsidiary");
            page.setSealInfos(java.util.List.of(own, subsidiary));
            return successful(page);
        });
        assertThat(gateway.listSeals("corp-3")).extracting(FadadaCompanyGateway.SealInfo::sealId)
                .containsExactly("1", "2");
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).getSealInfoListNew(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void malformedSealPaginationMustNotBeReturnedAsACompleteSnapshot() throws Exception {
        var properties = new FadadaProperties(); properties.setAppId("test");
        properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.SealClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "sealClient", client);
        var page = new com.fasc.open.api.v5_1.res.seal.GetSealInfoListRes();
        page.setListPageNo(1); page.setListPageCount(2); page.setSealInfos(java.util.List.of());
        org.mockito.Mockito.when(client.getSealInfoListNew(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> successful(page));
        assertThatThrownBy(() -> gateway.listSeals("corp-3")).hasMessageContaining("不完整");
    }

    @Test
    void memberLookupUsesTheSignedMemberIdAndReturnsOnlyIdentityAndAuthorityEvidence() throws Exception {
        var properties = new FadadaProperties(); properties.setAppId("test");
        properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.OrgClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "orgClient", client);
        var member = new com.fasc.open.api.v5_1.res.org.GetCorpMemberDetailRes();
        member.setMemberId(88L); member.setMemberStatus("activated"); member.setRoleType(java.util.List.of("super_admin"));
        member.setFddId("fdd-user-7"); member.setMemberMobile("13800138000");
        org.mockito.Mockito.when(client.getCorpMemberDetail(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var request = (com.fasc.open.api.v5_1.req.org.GetCorpMemberDetailReq) call.getArgument(0);
            assertThat(request.getMemberId()).isEqualTo(88L); assertThat(request.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(request.getInternalIdentifier()).isNull(); return successful(member);
        });
        assertThat(gateway.getMember("corp-3", 88L)).isEqualTo(new FadadaCompanyGateway.CompanyMember(
                88L, "activated", java.util.List.of("super_admin"), "fdd-user-7"));
    }

    @SuppressWarnings("unchecked")
    private <T> com.fasc.open.api.bean.base.BaseRes<T> successful(T data) {
        var response = (com.fasc.open.api.bean.base.BaseRes<T>) org.mockito.Mockito.mock(com.fasc.open.api.bean.base.BaseRes.class);
        org.mockito.Mockito.when(response.isSuccess()).thenReturn(true); org.mockito.Mockito.when(response.getData()).thenReturn(data);
        return response;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "13800138000"})
    @SuppressWarnings("unchecked")
    void authorizationUsesDocumentedNestedCompanyFieldsNativeReturnPageAndOptionalPhone(String accountName) throws Exception {
        var properties = new FadadaProperties();
        properties.setAppId("test"); properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var tokens = org.mockito.Mockito.mock(FadadaAccessTokenProvider.class);
        var gateway = new SdkFadadaCompanyGateway(properties, tokens);
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.CorpClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "corpClient", client);
        var detail = new com.fasc.open.api.v5_1.res.common.ECorpAuthUrlRes();
        detail.setAuthUrl("https://example.test/auth");
        com.fasc.open.api.bean.base.BaseRes<com.fasc.open.api.v5_1.res.common.ECorpAuthUrlRes> response =
                org.mockito.Mockito.mock(com.fasc.open.api.bean.base.BaseRes.class);
        org.mockito.Mockito.when(response.isSuccess()).thenReturn(true);
        org.mockito.Mockito.when(response.getData()).thenReturn(detail);
        org.mockito.Mockito.when(client.getCorpAuthUrl(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var request = (com.fasc.open.api.v5_1.req.corp.GetCorpAuthResourceUrlReq) call.getArgument(0);
            assertThat(request.getCorpIdentInfo().getCorpName()).isEqualTo("测试企业");
            assertThat(request.getCorpIdentInfo().getCorpIdentNo()).isEqualTo("TEST-CREDIT");
            assertThat(request.getCorpName()).isNull();
            assertThat(request.getCorpIdentNo()).isNull();
            assertThat(request.getCorpIdentInfoMatch()).isNull();
            assertThat(request.getCorpNonEditableInfo()).containsExactly("corpName", "corpIdentNo");
            assertThat(request.getClientUserId()).isEqualTo("user-7");
            assertThat(request.getAccountName()).isEqualTo(accountName == null || accountName.isBlank() ? null : accountName);
            assertThat(request.getRedirectMiniAppUrl()).isEqualTo("%2Fpages%2Fservice-return%2Fservice-return");
            return response;
        });
        assertThat(gateway.createAuthUrl(new FadadaCompanyGateway.AuthCommand("corp-3", "user-7", accountName,
                "测试企业", "TEST-CREDIT", java.util.List.of("ident_info"), "https://example.test/callback",
                "%2Fpages%2Fservice-return%2Fservice-return"))).isEqualTo("https://example.test/auth");
    }
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
    void businessInformationChangeRequiresTheConfirmedEnterpriseAndDoesNotChangeItsClientMapping() throws Exception {
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
