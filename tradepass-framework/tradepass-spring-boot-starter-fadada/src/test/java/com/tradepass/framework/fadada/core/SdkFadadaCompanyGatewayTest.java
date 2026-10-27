package com.tradepass.framework.fadada.core;

import com.tradepass.framework.fadada.config.FadadaProperties;

import com.fasc.open.api.v5_1.req.corp.GetCorpReq;
import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SdkFadadaCompanyGatewayTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"13800138000", "actual-fdd-user@example.test"})
    void automaticMemberCreationUsesOneVerifiedAccountWithoutAdministrativeRolesOrMerging(String account) throws Exception {
        var properties = new FadadaProperties(); properties.setAppId("test");
        properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.OrgClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "orgClient", client);
        org.mockito.Mockito.when(client.createCorpMember(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.fasc.open.api.v5_1.req.org.CreateCorpMemberReq request = call.getArgument(0);
            assertThat(request.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(request.getNotifyActiveByEmail()).isFalse();
            assertThat(request.getMergeMemberInfo()).isNull();
            assertThat(request.getRedirectUrl()).isNull();
            assertThat(request.getEmployeeInfos()).hasSize(1);
            var value = request.getEmployeeInfos().get(0);
            assertThat(value.getInternalIdentifier()).isEqualTo("tradepass-user-7");
            assertThat(value.getMemberName()).isEqualTo("武英龙");
            assertThat(value.getRoleType()).isNull();
            assertThat(value.getMemberDeptIds()).isNull();
            assertThat(value.getAccountNames()).isNull();
            assertThat(value.getMemberEmail()).isEqualTo(account.contains("@") ? account : null);
            assertThat(value.getMemberMobile()).isEqualTo(account.contains("@") ? null : account);
            var created = new com.fasc.open.api.v5_1.res.org.SimpleEmployeeInfo();
            created.setMemberId(88L); created.setInternalIdentifier("tradepass-user-7"); created.setMemberStatus("activated");
            return successful(java.util.List.of(created));
        });
        var result = gateway.createMember(new FadadaCompanyGateway.MemberCreateCommand("corp-3", "tradepass-user-7", "武英龙", account));
        assertThat(result.memberId()).isEqualTo(88L);
        assertThat(result.internalIdentifier()).isEqualTo("tradepass-user-7");
    }

    @Test
    void deterministicMemberRecoveryFindsTheSameAssociationWithoutCreatingAnotherMember() throws Exception {
        var properties = new FadadaProperties(); properties.setAppId("test");
        properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.OrgClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "orgClient", client);
        var value = new com.fasc.open.api.v5_1.res.org.EmployeeInfo();
        value.setMemberId(88L); value.setInternalIdentifier("tradepass-user-7");
        var page = new com.fasc.open.api.v5_1.res.org.GetMemberListRes(); page.setListPageNo(1);
        page.setListPageCount(1); page.setTotalCount(1); page.setCountInPage(1); page.setEmployeeInfos(java.util.List.of(value));
        org.mockito.Mockito.when(client.getMemberList(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> successful(page));
        var detail = new com.fasc.open.api.v5_1.res.org.GetCorpMemberDetailRes();
        detail.setMemberId(88L); detail.setInternalIdentifier("tradepass-user-7"); detail.setMemberStatus("activated");
        detail.setFddId("fdd-user-7");
        org.mockito.Mockito.when(client.getCorpMemberDetail(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> successful(detail));
        assertThat(gateway.findMemberByInternalIdentifier("corp-3", "tradepass-user-7").memberId()).isEqualTo(88L);
        assertThat(gateway.findMemberByInternalIdentifier("corp-3", "tradepass-user-9")).isNull();
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).createCorpMember(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void memberActivationReturnsOnlyTheSameMembersH5EmbedInvitation() throws Exception {
        var properties = new FadadaProperties(); properties.setAppId("test");
        properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.OrgClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "orgClient", client);
        var invitation = new com.fasc.open.api.v5_1.res.org.SimpleEmployeeInfo();
        invitation.setMemberId(88L); invitation.setMemberActiveUrl("https://example.test/short-link");
        invitation.setMemberActiveEmbedUrl("https://example.test/h5-embed");
        org.mockito.Mockito.when(client.getCorpMemberActiveUrl(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.fasc.open.api.v5_1.req.org.GetCorpMemberActiveUrlReq request = call.getArgument(0);
            assertThat(request.getOpenCorpId()).isEqualTo("corp-3"); assertThat(request.getMemberIds()).containsExactly(88L);
            assertThat(request.getRedirectUrl()).isNull(); return successful(java.util.List.of(invitation));
        });
        assertThat(gateway.createMemberActivationUrl("corp-3", 88L)).isEqualTo("https://example.test/h5-embed");
        invitation.setMemberId(99L);
        assertThatThrownBy(() -> gateway.createMemberActivationUrl("corp-3", 88L)).hasMessageContaining("本人加入企业地址");
    }

    @Test
    void memberCandidatesUseTheVerifiedNameFilterAndReadEveryDocumentedPage() throws Exception {
        var properties = new FadadaProperties(); properties.setAppId("test");
        properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.OrgClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "orgClient", client);
        org.mockito.Mockito.when(client.getMemberList(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.fasc.open.api.v5_1.req.org.GetMemberListReq request = call.getArgument(0);
            assertThat(request.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(request.getOwnerId()).isNull();
            assertThat(request.getListFilter().getMemberName()).isEqualTo("武英龙");
            assertThat(request.getListFilter().getRoleType()).isNull();
            assertThat(request.getListFilter().getMemberMobile()).isNull();
            assertThat(request.getListPageSize()).isEqualTo(100);
            var value = new com.fasc.open.api.v5_1.res.org.EmployeeInfo();
            value.setMemberId(80L + request.getListPageNo());
            var page = new com.fasc.open.api.v5_1.res.org.GetMemberListRes();
            page.setEmployeeInfos(java.util.List.of(value)); page.setListPageNo(request.getListPageNo());
            page.setCountInPage(1); page.setTotalCount(2); page.setListPageCount(2);
            return successful(page);
        });
        assertThat(gateway.listMemberIds("corp-3", "武英龙")).containsExactly(81L, 82L);
    }

    @Test
    void missingOrConflictingMemberPagesCannotBeTreatedAsACompleteMembershipResult() throws Exception {
        for (String malformed : java.util.List.of("empty-page", "duplicate-member", "changed-total")) {
            var properties = new FadadaProperties(); properties.setAppId("test");
            properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
            var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
            var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.OrgClient.class);
            org.springframework.test.util.ReflectionTestUtils.setField(gateway, "orgClient", client);
            org.mockito.Mockito.when(client.getMemberList(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                com.fasc.open.api.v5_1.req.org.GetMemberListReq request = call.getArgument(0);
                var value = new com.fasc.open.api.v5_1.res.org.EmployeeInfo(); value.setMemberId(88L);
                var page = new com.fasc.open.api.v5_1.res.org.GetMemberListRes();
                page.setEmployeeInfos("empty-page".equals(malformed) ? java.util.List.of() : java.util.List.of(value));
                page.setListPageNo(request.getListPageNo()); page.setCountInPage(page.getEmployeeInfos().size());
                page.setTotalCount("changed-total".equals(malformed) ? request.getListPageNo() : 2); page.setListPageCount(2);
                return successful(page);
            });
            assertThatThrownBy(() -> gateway.listMemberIds("corp-3", "武英龙"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("成员列表");
        }
    }

    @Test
    void organizationManagementUsesTheOfficialPcMemberModuleAndCurrentLoginMapping() throws Exception {
        var properties = new FadadaProperties(); properties.setAppId("test");
        properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.OrgClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "orgClient", client);
        org.mockito.Mockito.when(client.getCorpOrgManageUrl(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.fasc.open.api.v5_1.req.org.GetCorpOrgManageUrlReq request = call.getArgument(0);
            assertThat(request.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(request.getClientUserId()).isEqualTo("tradepass-user-7");
            assertThat(request.getModules()).containsExactly("member");
            assertThat(request.getRedirectUrl()).isNull();
            var value = new com.fasc.open.api.v5_1.res.org.GetCorpOrgManageUrlRes();
            value.setResourceUrl("https://example.test/organization"); return successful(value);
        });
        assertThat(gateway.createOrganizationManageUrl("corp-3", "tradepass-user-7"))
                .isEqualTo("https://example.test/organization");
    }

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
                88L, "activated", java.util.List.of("super_admin"), "fdd-user-7", null, java.util.List.of("13800138000")));
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
            assertThat(request.getCorpIdentInfo().getCorpIdentMethod()).isNull();
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void onlyTheOnboardingCommandRestrictsCertificationToTheLegalRepresentativesFace(boolean legalRepresentativeOnly)
            throws Exception {
        var properties = new FadadaProperties();
        properties.setAppId("test"); properties.setAppSecret("test"); properties.setServerUrl("https://example.test");
        properties.setLegalRepresentativeOnlyOnboardingEnabled(true);
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class));
        var client = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.CorpClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "corpClient", client);
        var detail = new com.fasc.open.api.v5_1.res.common.ECorpAuthUrlRes();
        detail.setAuthUrl("https://example.test/auth");
        org.mockito.Mockito.when(client.getCorpAuthUrl(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var request = (com.fasc.open.api.v5_1.req.corp.GetCorpAuthResourceUrlReq) call.getArgument(0);
            if (legalRepresentativeOnly) {
                assertThat(request.getCorpIdentInfo().getCorpIdentMethod()).containsExactly("legalRepFace");
            } else {
                assertThat(request.getCorpIdentInfo().getCorpIdentMethod()).isNull();
            }
            // The public contract does not allow locking the certification method through this list.
            assertThat(request.getCorpNonEditableInfo()).containsExactly("corpName", "corpIdentNo");
            assertThat(request.getClientUserId()).isEqualTo("user-7");
            return successful(detail);
        });
        assertThat(gateway.createAuthUrl(new FadadaCompanyGateway.AuthCommand("corp-3", "user-7", null,
                "测试企业", "TEST-CREDIT", java.util.List.of("ident_info"), "https://example.test/callback",
                null, legalRepresentativeOnly))).isEqualTo("https://example.test/auth");
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
        assertThat(result.operatorOpenUserIdDocumented()).isFalse();
    }

    @Test
    void missingModernOperatorUsesOneCompleteMatchingDocumentedLegacyIdentity() throws Exception {
        var primary = identifiedResponse();
        var legacy = legacyIdentity();
        var client = org.mockito.Mockito.mock(LegacyFadadaCompanyIdentityClient.class);
        org.mockito.Mockito.when(client.getIdentity("corp-3")).thenReturn(legacy);
        var gateway = identityGateway(primary, client);

        assertThat(gateway.getIdentity("corp-3")).isSameAs(legacy);
        org.mockito.Mockito.verify(client).getIdentity("corp-3");
    }

    @Test
    void completeUndocumentedModernOperatorStillNeedsTheMatchingDocumentedLegacySource() throws Exception {
        var primary = identifiedResponse();
        primary.setOperatorType("legal_rep"); primary.setOperatorId("open-user-7");
        var client = org.mockito.Mockito.mock(LegacyFadadaCompanyIdentityClient.class);
        var legacy = legacyIdentity();
        org.mockito.Mockito.when(client.getIdentity("corp-3")).thenReturn(legacy);

        var result = identityGateway(primary, client).getIdentity("corp-3");

        assertThat(result).isSameAs(legacy);
        assertThat(result.operatorOpenUserIdDocumented()).isTrue();
        org.mockito.Mockito.verify(client).getIdentity("corp-3");
    }

    @Test
    void completeConflictingModernOperatorCannotAcquireTheLegacyTrustMarker() throws Exception {
        var primary = identifiedResponse();
        primary.setOperatorType("deputy_auth"); primary.setOperatorId("open-user-8");
        var client = org.mockito.Mockito.mock(LegacyFadadaCompanyIdentityClient.class);
        org.mockito.Mockito.when(client.getIdentity("corp-3")).thenReturn(legacyIdentity());

        var result = identityGateway(primary, client).getIdentity("corp-3");

        assertThat(result).isEqualTo(SdkFadadaCompanyGateway.toCompanyIdentity(primary));
        assertThat(result.operatorOpenUserIdDocumented()).isFalse();
        org.mockito.Mockito.verify(client).getIdentity("corp-3");
    }

    @Test
    void legacyFailureOrMissingFieldsLeavesTheModernResultUnchanged() throws Exception {
        var primary = identifiedResponse();
        var client = org.mockito.Mockito.mock(LegacyFadadaCompanyIdentityClient.class);
        var gateway = identityGateway(primary, client);
        assertThat(gateway.getIdentity("corp-3")).isEqualTo(SdkFadadaCompanyGateway.toCompanyIdentity(primary));
        org.mockito.Mockito.when(client.getIdentity("corp-3")).thenThrow(new IllegalStateException("private response"));
        assertThat(gateway.getIdentity("corp-3")).isEqualTo(SdkFadadaCompanyGateway.toCompanyIdentity(primary));
    }

    @Test
    void legacyAuthorityMustMatchTheRequestedEnterpriseAndTheEntireCurrentIdentityVersion() throws Exception {
        java.util.List<java.util.function.Consumer<com.fasc.open.api.v5_1.res.corp.CorpIdentityInfoRes>> changes = java.util.List.of(
                value -> value.setOpenCorpId("different-corp"),
                value -> value.setCorpIdentStatus("unidentified"),
                value -> value.getCorpIdentInfo().setCorpName("另一企业"),
                value -> value.getCorpIdentInfo().setCorpIdentNo("OTHER-CREDIT"),
                value -> value.getCorpIdentInfo().setLegalRepName("另一法人"),
                value -> value.setCorpIdentMethod("deputy_auth"),
                value -> value.setIdentSuccessTime("1700000000001"),
                value -> value.setIdentSuccessTime(null),
                value -> value.setOperatorId("different-operator"),
                value -> value.setOperatorType("deputy_auth"));
        for (var change : changes) {
            var primary = identifiedResponse();
            change.accept(primary);
            var client = org.mockito.Mockito.mock(LegacyFadadaCompanyIdentityClient.class);
            org.mockito.Mockito.when(client.getIdentity("corp-3")).thenReturn(legacyIdentity());
            assertThat(identityGateway(primary, client).getIdentity("corp-3"))
                    .isEqualTo(SdkFadadaCompanyGateway.toCompanyIdentity(primary));
        }
    }

    private com.fasc.open.api.v5_1.res.corp.CorpIdentityInfoRes identifiedResponse() {
        var response = new com.fasc.open.api.v5_1.res.corp.CorpIdentityInfoRes();
        var company = new com.fasc.open.api.bean.common.CorpIdentInfo();
        company.setCorpName("测试企业"); company.setCorpIdentNo("TEST-CREDIT"); company.setLegalRepName("测试法人");
        response.setOpenCorpId("corp-3"); response.setCorpIdentStatus("identified"); response.setCorpIdentInfo(company);
        response.setCorpIdentMethod("legal_rep"); response.setIdentSubmitTime("1699999900000");
        response.setIdentSuccessTime("1700000000000");
        return response;
    }

    private FadadaCompanyGateway.CompanyIdentity legacyIdentity() {
        return new FadadaCompanyGateway.CompanyIdentity("corp-3", "identified", "测试企业", "TEST-CREDIT", "测试法人",
                "legal_rep", "1699999900000", "1700000000000", "legal_rep", "open-user-7", true);
    }

    private SdkFadadaCompanyGateway identityGateway(com.fasc.open.api.v5_1.res.corp.CorpIdentityInfoRes response,
                                                  LegacyFadadaCompanyIdentityClient legacy) throws Exception {
        var properties = LegacyFadadaCompanyIdentityClientTest.properties();
        var gateway = new SdkFadadaCompanyGateway(properties, org.mockito.Mockito.mock(FadadaAccessTokenProvider.class), legacy);
        var corpClient = org.mockito.Mockito.mock(com.fasc.open.api.v5_1.client.CorpClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "corpClient", corpClient);
        org.mockito.Mockito.when(corpClient.getIdentityInfo(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> successful(response));
        return gateway;
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
