package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.exception.ApiException;
import com.fasc.open.api.v5_1.client.SignTaskClient;
import com.fasc.open.api.v5_1.req.signtask.BlockReq;
import com.fasc.open.api.v5_1.req.signtask.UnblockReq;
import com.fasc.open.api.v5_1.res.signtask.ListSignTaskActorRes;
import com.fasc.open.api.v5_1.res.signtask.SignTaskDetailRes;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SdkFadadaSigningCancellationGatewayTest {
    @Test void blockAndUnblockTargetTheExactParticipantWithoutCreatingOrCancellingATask() throws Exception {
        var f = new Fixture();
        when(f.client.block(any())).thenAnswer(call -> {
            BlockReq request = call.getArgument(0);
            assertThat(request.getSignTaskId()).isEqualTo("task-12");
            assertThat(request.getActorId()).isEqualTo("supplier");
            assertThat(request.getAccessToken()).isEqualTo("token");
            return success(null);
        });
        when(f.client.unblock(any())).thenAnswer(call -> {
            UnblockReq request = call.getArgument(0);
            assertThat(request.getSignTaskId()).isEqualTo("task-12");
            assertThat(request.getActorId()).isEqualTo("supplier");
            assertThat(request.getAccessToken()).isEqualTo("token");
            return success(null);
        });

        f.gateway.blockActor("task-12", "supplier");
        f.gateway.unblockActor("task-12", "supplier");

        verify(f.client).block(any());
        verify(f.client).unblock(any());
        verifyNoMoreInteractions(f.client);
    }

    @Test void actorEvidenceRetainsBothASigningTimestampAndTheProviderBlockState() throws Exception {
        var f = new Fixture();
        var detail = new SignTaskDetailRes();
        detail.setSignTaskId("task-12"); detail.setSignTaskStatus("sign_progress");
        when(f.client.querySignTaskDetail(any())).thenAnswer(call -> success(detail));
        var supplier = new ListSignTaskActorRes();
        supplier.setActorId("supplier"); supplier.setSignStatus("wait_sign");
        // Do not erase contradictory evidence: callers must refuse an unsigned refund if a timestamp exists.
        supplier.setSignTime("1792994359000"); supplier.setBlockStatus("blocked");
        var buyer = new ListSignTaskActorRes();
        buyer.setActorId("buyer"); buyer.setSignStatus("wait_sign"); buyer.setBlockStatus("unblocked");
        when(f.client.listSignTaskActor(any())).thenAnswer(call -> success(List.of(supplier, buyer)));

        assertThat(f.gateway.status("task-12").actors()).containsExactly(
                new FadadaSigningGateway.ActorStatus("supplier", "wait_sign", "1792994359000", "blocked"),
                new FadadaSigningGateway.ActorStatus("buyer", "wait_sign", null, "unblocked"));
    }

    @Test void aRejectedBlockCannotBeMistakenForASuccessfulSigningFreeze() throws Exception {
        var f = new Fixture();
        BaseRes<Void> rejected = new BaseRes<>();
        rejected.setCode("100001"); rejected.setMsg("provider private details");
        when(f.client.block(any())).thenReturn(rejected);

        assertThatThrownBy(() -> f.gateway.blockActor("task-12", "supplier"))
                .isInstanceOf(BusinessException.class).hasMessage("阻塞合同签署参与方失败，请稍后重试");
        verify(f.client, times(1)).block(any());
    }

    @Test void anUnblockTransportFailureRemainsVisibleForDurableRecoveryAndIsNotReplayed() throws Exception {
        var f = new Fixture();
        when(f.client.unblock(any())).thenThrow(new ApiException("transport unavailable"));

        assertThatThrownBy(() -> f.gateway.unblockActor("task-12", "supplier"))
                .isInstanceOf(BusinessException.class).hasMessage("解阻合同签署参与方失败，请稍后重试");
        verify(f.client, times(1)).unblock(any());
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" "})
    void anInvalidParticipantNeverContactsTheProvider(String actorId) {
        var f = new Fixture();
        assertThatThrownBy(() -> f.gateway.blockActor("task-12", actorId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> f.gateway.unblockActor("task-12", actorId)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(f.client, f.tokens);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" "})
    void anInvalidTaskNeverContactsTheProvider(String taskId) {
        var f = new Fixture();
        assertThatThrownBy(() -> f.gateway.blockActor(taskId, "supplier")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> f.gateway.unblockActor(taskId, "supplier")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(f.client, f.tokens);
    }

    @Test void legacyActorConstructionKeepsMissingEvidenceExplicit() {
        var actor = new FadadaSigningGateway.ActorStatus("supplier", "wait_sign");
        assertThat(actor.signTime()).isNull();
        assertThat(actor.blockStatus()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static <T> BaseRes<T> success(T data) {
        var response = (BaseRes<T>) mock(BaseRes.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getData()).thenReturn(data);
        return response;
    }

    private static class Fixture {
        final SignTaskClient client = mock(SignTaskClient.class);
        final FadadaAccessTokenProvider tokens = mock(FadadaAccessTokenProvider.class);
        final SdkFadadaSigningGateway gateway;
        Fixture() {
            var properties = new FadadaProperties();
            properties.setAppId("test"); properties.setAppSecret("test");
            properties.setServerUrl("https://example.test");
            when(tokens.get()).thenReturn("token");
            gateway = new SdkFadadaSigningGateway(properties, tokens);
            ReflectionTestUtils.setField(gateway, "signTaskClient", client);
        }
    }
}
