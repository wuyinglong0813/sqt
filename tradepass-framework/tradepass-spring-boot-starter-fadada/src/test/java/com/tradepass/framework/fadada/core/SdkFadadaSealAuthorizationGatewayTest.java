package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.v5_1.client.SealClient;
import com.fasc.open.api.v5_1.req.seal.GetSealGrantUrlReq;
import com.fasc.open.api.v5_1.req.seal.GetUserSealListReq;
import com.fasc.open.api.v5_1.res.seal.GetSealGrantUrlRes;
import com.fasc.open.api.v5_1.res.seal.GetUserListSealInfo;
import com.fasc.open.api.v5_1.res.seal.GetUserSealListRes;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SdkFadadaSealAuthorizationGatewayTest {
    @Test
    void queriesTheEmployeesOwnSealGrantsAndPreservesTheirCurrentGrantStatus() throws Exception {
        var f = new Fixture();
        var response = new GetUserSealListRes();
        var seal = new GetUserListSealInfo();
        seal.setSealId(912L); seal.setSealStatus("enable"); seal.setGrantStatus("ineffective");
        response.setSealInfos(List.of(seal));
        when(f.client.getUserSealList(any())).thenAnswer(call -> {
            GetUserSealListReq request = call.getArgument(0);
            assertThat(request.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(request.getMemberId()).isEqualTo(88L);
            return successful(response);
        });

        assertThat(f.gateway.memberSealGrants("corp-3", 88L)).containsExactly(
                new FadadaSealAuthorizationGateway.MemberSealGrant("912", "enable", "ineffective"));
    }

    @Test
    void preselectsTheActualEmployeeAndSealWhileKeepingTheManagerAsTheLoginUser() throws Exception {
        var f = new Fixture();
        var response = new GetSealGrantUrlRes(); response.setSealGrantUrl("https://example.test/seal/grant");
        when(f.client.getSealGrantUrl(any())).thenAnswer(call -> {
            GetSealGrantUrlReq request = call.getArgument(0);
            assertThat(request.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(request.getSealId()).isEqualTo(912L);
            assertThat(request.getMemberInfo().getMemberIds()).containsExactly(88L);
            assertThat(request.getClientUserId()).isEqualTo("tradepass-user-99");
            assertThat(request.getMemberInfo().getGrantStartTime()).isNull();
            assertThat(request.getMemberInfo().getGrantEndTime()).isNull();
            assertThat(request.getRedirectUrl()).isNull();
            return successful(response);
        });

        assertThat(f.gateway.memberSealGrantUrl("corp-3", 912L, 88L, "tradepass-user-99"))
                .isEqualTo("https://example.test/seal/grant");
    }

    @Test
    void incompleteOrDuplicateGrantResponsesAreNotUsedAsAnAuthorizationDecision() throws Exception {
        var f = new Fixture(); var response = new GetUserSealListRes();
        when(f.client.getUserSealList(any())).thenAnswer(call -> successful(response));
        assertThatThrownBy(() -> f.gateway.memberSealGrants("corp-3", 88L)).hasMessageContaining("不完整");
        var seal = new GetUserListSealInfo(); seal.setSealId(912L);
        response.setSealInfos(List.of(seal));
        assertThatThrownBy(() -> f.gateway.memberSealGrants("corp-3", 88L)).hasMessageContaining("不完整");
        seal.setSealStatus("enable"); seal.setGrantStatus("effective");
        response.setSealInfos(List.of(seal, seal));
        assertThatThrownBy(() -> f.gateway.memberSealGrants("corp-3", 88L)).hasMessageContaining("不完整");
    }

    @Test
    void anEmptyGrantListIsDistinctFromAnIncompleteResponse() throws Exception {
        var f = new Fixture(); var response = new GetUserSealListRes(); response.setSealInfos(List.of());
        when(f.client.getUserSealList(any())).thenAnswer(call -> successful(response));
        assertThat(f.gateway.memberSealGrants("corp-3", 88L)).isEmpty();
    }

    @Test
    void rejectsUnsafeProviderAddressesAndInvalidMemberIdentifiers() throws Exception {
        var f = new Fixture(); var response = new GetSealGrantUrlRes();
        when(f.client.getSealGrantUrl(any())).thenAnswer(call -> successful(response));
        response.setSealGrantUrl("http://example.test/grant");
        assertThatThrownBy(() -> f.gateway.memberSealGrantUrl("corp-3", 912L, 88L, "user-99"))
                .hasMessageContaining("有效的成员用印授权地址");
        response.setSealGrantUrl("https://user:password@example.test/grant");
        assertThatThrownBy(() -> f.gateway.memberSealGrantUrl("corp-3", 912L, 88L, "user-99"))
                .hasMessageContaining("有效的成员用印授权地址");
        assertThatThrownBy(() -> f.gateway.memberSealGrants("corp-3", 0)).hasMessageContaining("参数不完整");
    }

    @SuppressWarnings("unchecked")
    private static <T> BaseRes<T> successful(T value) {
        BaseRes<T> result = mock(BaseRes.class);
        when(result.isSuccess()).thenReturn(true); when(result.getData()).thenReturn(value);
        return result;
    }

    private static class Fixture {
        final SealClient client = mock(SealClient.class);
        final SdkFadadaSealAuthorizationGateway gateway;
        Fixture() {
            var properties = new FadadaProperties();
            properties.setAppId("app"); properties.setAppSecret("secret"); properties.setServerUrl("https://example.test");
            gateway = new SdkFadadaSealAuthorizationGateway(properties, mock(FadadaAccessTokenProvider.class));
            ReflectionTestUtils.setField(gateway, "sealClient", client);
        }
    }
}
