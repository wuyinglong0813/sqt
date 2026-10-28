package com.tradepass.module.contract.service.signing;

import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.service.membership.MembershipService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SigningRefundRecoveryProcessorTest {
    @ParameterizedTest @ValueSource(strings = {"sign_progress", "finish_creation", "task_finished", "unknown"})
    void aHistoricalRefundRequestNeverCancelsALiveOrUnconfirmedProviderTask(String status) {
        var f = new Fixture(status, false);

        assertThatThrownBy(() -> f.processor.process(f.task, f.intent))
                .hasMessageContaining("尚未确认终止");

        verify(f.gateway, never()).cancel(anyString(), anyString());
        verify(f.gateway, never()).blockActor(anyString(), anyString());
        verify(f.gateway, never()).unblockActor(anyString(), anyString());
        verifyNoInteractions(f.membership);
    }

    @ParameterizedTest @ValueSource(strings = {"task_terminated", "expired", "task_expired"})
    void aStoppedHistoricalTaskWithBothActorsUnsignedOnlyReturnsItsOriginalQuota(String status) {
        var f = new Fixture(status, false);

        assertThat(f.processor.process(f.task, f.intent)).isEqualTo(SigningCancellationProcessor.Result.CANCELLED);

        verify(f.membership).refundCancelledUnsigned(12L, 1, "old-task");
        verify(f.gateway, never()).cancel(anyString(), anyString());
        verify(f.gateway, never()).blockActor(anyString(), anyString());
        verify(f.gateway, never()).unblockActor(anyString(), anyString());
    }

    @Test void aSignatureFoundOnTheStoppedHistoricalTaskPreventsAnyQuotaRefund() {
        var f = new Fixture("task_terminated", true);

        assertThat(f.processor.process(f.task, f.intent)).isEqualTo(SigningCancellationProcessor.Result.CANCELLED);

        verifyNoInteractions(f.membership);
        verify(f.gateway, never()).cancel(anyString(), anyString());
    }

    private static class Fixture {
        final FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
        final SigningCancellationIntentStore intents = mock(SigningCancellationIntentStore.class);
        final MembershipService membership = mock(MembershipService.class);
        final FadadaContractSignTaskDO task = new FadadaContractSignTaskDO();
        final SigningCancellationIntentStore.Intent intent = new SigningCancellationIntentStore.Intent(
                "old-task", 12, 1, false, SigningCancellationProcessor.REFUND_RECOVERY_REASON, List.of());
        final SigningCancellationProcessor processor = new SigningCancellationProcessor(gateway, intents, membership);
        Fixture(String state, boolean signed) {
            task.setContractId(12L); task.setVersionNo(1); task.setSignTaskId("old-task");
            task.setInitiatorActorId("supplier"); task.setCounterpartyActorId("buyer");
            var supplier = new FadadaSigningGateway.ActorStatus("supplier", signed ? "signed" : "wait_sign",
                    signed ? "1792994359000" : null, "unblocked");
            var buyer = new FadadaSigningGateway.ActorStatus("buyer", "wait_sign", null, "unblocked");
            when(gateway.status("old-task")).thenReturn(new FadadaSigningGateway.TaskStatus(
                    "old-task", state, List.of(supplier, buyer)));
            when(intents.ownedActors("old-task")).thenReturn(List.of());
        }
    }
}
