package com.tradepass.module.contract.service.signing;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.service.membership.MembershipService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SigningCancellationProcessorTest {
    private static final String TASK = "current-v2-task";
    private static final String SUPPLIER = "supplier";
    private static final String BUYER = "buyer";

    @Test void automaticWithdrawalBlocksBothPartiesAndChecksAgainBeforeCancellingAndRefunding() {
        var f = new Fixture();

        assertThat(f.processor.process(f.task, f.intent(true))).isEqualTo(SigningCancellationProcessor.Result.CANCELLED);

        var order = inOrder(f.gateway, f.store, f.membership);
        order.verify(f.gateway).status(TASK);
        order.verify(f.store).rememberOwnedActor(TASK, SUPPLIER);
        order.verify(f.gateway).blockActor(TASK, SUPPLIER);
        order.verify(f.store).rememberOwnedActor(TASK, BUYER);
        order.verify(f.gateway).blockActor(TASK, BUYER);
        order.verify(f.gateway).status(TASK);
        order.verify(f.gateway).cancel(TASK, SigningCancellationProcessor.TIMEOUT_REASON);
        order.verify(f.gateway).status(TASK);
        order.verify(f.membership).refundCancelledUnsigned(12L, 2, TASK);
        assertThat(f.task.getProviderStatus()).isEqualTo("task_terminated");
        assertThat(f.task.getLastError()).isEqualTo(SigningCancellationProcessor.TIMEOUT_REASON);
        assertThat(f.owned).isEmpty();
        verify(f.gateway, never()).unblockActor(anyString(), anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"signed", "sign_completed", "completed"})
    void eitherPartysSignaturePreventsAnyAutomaticBlockCancellationOrRefund(String signStatus) {
        for (String actor : List.of(SUPPLIER, BUYER)) {
            var f = new Fixture();
            f.actor(actor, signStatus, null, "unblocked");

            assertThat(f.processor.process(f.task, f.intent(true))).isEqualTo(SigningCancellationProcessor.Result.SIGNED);

            verify(f.gateway, never()).blockActor(anyString(), anyString());
            verify(f.gateway, never()).cancel(anyString(), anyString());
            verifyNoInteractions(f.membership);
        }
    }

    @Test void aSigningTimestampAloneProtectsAnApparentlyWaitingTask() {
        var f = new Fixture();
        f.actor(BUYER, "wait_sign", "1792994359000", "unblocked");

        assertThat(f.processor.process(f.task, f.intent(true))).isEqualTo(SigningCancellationProcessor.Result.SIGNED);

        verify(f.gateway, never()).blockActor(anyString(), anyString());
        verify(f.gateway, never()).cancel(anyString(), anyString());
        verifyNoInteractions(f.membership);
    }

    @Test void previouslyConfirmedLocalSignatureIsNotErasedByAStaleProviderResponse() {
        var f = new Fixture();
        f.task.setInitiatorSignStatus("signed");

        assertThat(f.processor.process(f.task, f.intent(true))).isEqualTo(SigningCancellationProcessor.Result.SIGNED);

        assertThat(f.task.getInitiatorSignStatus()).isEqualTo("signed");
        verify(f.gateway, never()).blockActor(anyString(), anyString());
        verify(f.gateway, never()).cancel(anyString(), anyString());
        verifyNoInteractions(f.membership);
    }

    @Test void aSignatureDiscoveredAfterBlockingRestoresOurBlocksAndPreservesTheTask() {
        var f = new Fixture();
        doAnswer(invocation -> {
            f.actor(BUYER, "wait_sign", null, "blocked");
            f.actor(SUPPLIER, "signed", "1792994359000", "blocked");
            return null;
        }).when(f.gateway).blockActor(TASK, BUYER);

        assertThat(f.processor.process(f.task, f.intent(true))).isEqualTo(SigningCancellationProcessor.Result.SIGNED);

        verify(f.gateway).unblockActor(TASK, SUPPLIER);
        verify(f.gateway).unblockActor(TASK, BUYER);
        verify(f.gateway, never()).cancel(anyString(), anyString());
        verifyNoInteractions(f.membership);
        assertThat(f.task.getInitiatorSignStatus()).isEqualTo("signed");
        assertThat(f.task.getProviderStatus()).isEqualTo("sign_progress");
        assertThat(f.owned).isEmpty();
    }

    @Test void failedBlockingRestoresOnlyTheBlocksOwnedByThisWorkflow() {
        var f = new Fixture();
        f.actor(BUYER, "wait_sign", null, "blocked");
        doAnswer(invocation -> {
            // Simulate a successful provider side effect whose response was lost.
            f.actor(SUPPLIER, "wait_sign", null, "blocked");
            throw new BusinessException("暂停响应丢失");
        }).when(f.gateway).blockActor(TASK, SUPPLIER);

        assertThatThrownBy(() -> f.processor.process(f.task, f.intent(true))).hasMessage("暂停响应丢失");

        verify(f.gateway).unblockActor(TASK, SUPPLIER);
        verify(f.gateway, never()).blockActor(TASK, BUYER);
        verify(f.gateway, never()).unblockActor(TASK, BUYER);
        verify(f.store, never()).rememberOwnedActor(TASK, BUYER);
        assertThat(f.remote.get().actors().get(1).blockStatus()).isEqualTo("blocked");
        assertThat(f.owned).isEmpty();
        verify(f.gateway, never()).cancel(anyString(), anyString());
        verifyNoInteractions(f.membership);
    }

    @Test void aBlockNotConfirmedByTheProviderDoesNotPermitCancellation() {
        var f = new Fixture();
        doNothing().when(f.gateway).blockActor(TASK, BUYER);

        assertThatThrownBy(() -> f.processor.process(f.task, f.intent(true))).hasMessageContaining("未确认双方签署均已暂停");

        verify(f.gateway, never()).cancel(anyString(), anyString());
        verify(f.gateway).unblockActor(TASK, SUPPLIER);
        verify(f.gateway, never()).unblockActor(TASK, BUYER);
        verifyNoInteractions(f.membership);
        assertThat(f.owned).isEmpty();
    }

    @Test void aLostCancellationResponseAndUnavailableQueriesKeepOwnershipForRecovery() {
        var f = new Fixture();
        var disconnected = new AtomicBoolean();
        when(f.gateway.status(TASK)).thenAnswer(invocation -> {
            if (disconnected.get()) throw new BusinessException("查询暂不可用");
            return f.remote.get();
        });
        doAnswer(invocation -> {
            f.state("task_terminated");
            disconnected.set(true);
            throw new BusinessException("撤回响应丢失");
        }).when(f.gateway).cancel(eq(TASK), anyString());

        assertThatThrownBy(() -> f.processor.process(f.task, f.intent(true)))
                .hasMessage("撤回响应丢失").satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));

        assertThat(f.owned).containsExactly(SUPPLIER, BUYER);
        verify(f.store, never()).forgetOwnedActor(anyString(), anyString());
        verifyNoInteractions(f.membership);

        // A later recovery observes the provider's actual outcome and does not cancel twice.
        disconnected.set(false);
        assertThat(f.processor.process(f.task, f.intent(true))).isEqualTo(SigningCancellationProcessor.Result.CANCELLED);
        verify(f.gateway, times(1)).cancel(TASK, SigningCancellationProcessor.TIMEOUT_REASON);
        verify(f.membership, times(1)).refundCancelledUnsigned(12L, 2, TASK);
        assertThat(f.owned).isEmpty();
    }

    @Test void anUnavailablePostBlockQueryKeepsOwnedActorsUntilARecoveryCanUnblockThem() {
        var f = new Fixture();
        var disconnected = new AtomicBoolean();
        when(f.gateway.status(TASK)).thenAnswer(invocation -> {
            if (disconnected.get()) throw new BusinessException("查询暂不可用");
            return f.remote.get();
        });
        doAnswer(invocation -> {
            f.actor(BUYER, "wait_sign", null, "blocked");
            disconnected.set(true);
            return null;
        }).when(f.gateway).blockActor(TASK, BUYER);

        assertThatThrownBy(() -> f.processor.process(f.task, f.intent(true))).hasMessage("查询暂不可用");
        assertThat(f.owned).containsExactly(SUPPLIER, BUYER);
        verify(f.gateway, never()).cancel(anyString(), anyString());
        verifyNoInteractions(f.membership);

        disconnected.set(false);
        f.processor.restoreOwnedBlocks(f.task);
        verify(f.gateway).unblockActor(TASK, SUPPLIER);
        verify(f.gateway).unblockActor(TASK, BUYER);
        assertThat(f.owned).isEmpty();
    }

    @Test void unconfirmedActorEvidenceAfterCancellationNeverRefundsQuota() {
        var f = new Fixture();
        doAnswer(invocation -> {
            f.state("task_terminated");
            f.actor(BUYER, "unknown", null, "blocked");
            return null;
        }).when(f.gateway).cancel(eq(TASK), anyString());

        assertThatThrownBy(() -> f.processor.process(f.task, f.intent(true))).hasMessageContaining("暂不退回额度");

        verify(f.gateway).cancel(TASK, SigningCancellationProcessor.TIMEOUT_REASON);
        verifyNoInteractions(f.membership);
        assertThat(f.owned).isEmpty();
        assertThat(f.task.getLastError()).isNull();
    }

    @Test void aManualWithdrawalCanStopAPartiallySignedTaskButCannotRefundIt() {
        var f = new Fixture();
        f.actor(SUPPLIER, "signed", "1792994359000", "unblocked");

        assertThat(f.processor.process(f.task, f.intent(false))).isEqualTo(SigningCancellationProcessor.Result.CANCELLED);

        verify(f.gateway).cancel(TASK, "发起方撤回合同");
        verify(f.gateway, never()).blockActor(anyString(), anyString());
        verifyNoInteractions(f.membership);
        assertThat(f.task.getInitiatorSignStatus()).isEqualTo("signed");
    }

    @Test void aManualUnsignedWithdrawalRefundsOnlyAfterTerminationHasBeenConfirmed() {
        var f = new Fixture();

        assertThat(f.processor.process(f.task, f.intent(false))).isEqualTo(SigningCancellationProcessor.Result.CANCELLED);

        var order = inOrder(f.gateway, f.membership);
        order.verify(f.gateway).status(TASK);
        order.verify(f.gateway).cancel(TASK, "发起方撤回合同");
        order.verify(f.gateway).status(TASK);
        order.verify(f.membership).refundCancelledUnsigned(12L, 2, TASK);
        verify(f.gateway, never()).blockActor(anyString(), anyString());
    }

    @Test void aCancellationWhoseProviderStateRemainsActiveNeverRefunds() {
        var f = new Fixture();
        doNothing().when(f.gateway).cancel(eq(TASK), anyString());

        assertThatThrownBy(() -> f.processor.process(f.task, f.intent(false))).hasMessageContaining("尚未确认");

        verifyNoInteractions(f.membership);
        assertThat(f.task.getProviderStatus()).isEqualTo("sign_progress");
    }

    @Test void mismatchedIntentNeverContactsTheProvider() {
        var f = new Fixture();
        var otherVersion = new SigningCancellationIntentStore.Intent(TASK, 12L, 3, true, "撤回", List.of());

        assertThatThrownBy(() -> f.processor.process(f.task, otherVersion)).hasMessageContaining("合同版本不一致");

        verifyNoInteractions(f.gateway, f.store, f.membership);
    }

    @Test void anotherTasksStatusCannotAuthorizeCancellationOrRefund() {
        var f = new Fixture();
        f.remote.set(new FadadaSigningGateway.TaskStatus("foreign-task", "task_terminated", f.remote.get().actors()));

        assertThatThrownBy(() -> f.processor.process(f.task, f.intent(true))).hasMessageContaining("无法确认签署任务状态");

        verify(f.gateway, never()).blockActor(anyString(), anyString());
        verify(f.gateway, never()).cancel(anyString(), anyString());
        verifyNoInteractions(f.membership);
    }

    @Test void missingUnknownAndDuplicateActorsCannotAuthorizeAnAutomaticRefund() {
        for (List<FadadaSigningGateway.ActorStatus> actors : List.of(
                List.of(actor(SUPPLIER, "wait_sign", null, "unblocked")),
                List.of(actor(SUPPLIER, "wait_sign", null, "unblocked"), actor("foreign", "wait_sign", null, "unblocked")),
                List.of(actor(SUPPLIER, "wait_sign", null, "unblocked"), actor(SUPPLIER, "wait_sign", null, "unblocked")))) {
            var f = new Fixture();
            f.remote.set(new FadadaSigningGateway.TaskStatus(TASK, "sign_progress", actors));

            assertThatThrownBy(() -> f.processor.process(f.task, f.intent(true))).hasMessageContaining("证据尚未齐全");

            verify(f.gateway, never()).blockActor(anyString(), anyString());
            verify(f.gateway, never()).cancel(anyString(), anyString());
            verifyNoInteractions(f.membership);
        }
    }

    @Test void aManualStoppedTaskWithUnknownActorsDoesNotRefund() {
        var f = new Fixture();
        f.remote.set(new FadadaSigningGateway.TaskStatus(TASK, "task_terminated",
                List.of(actor(SUPPLIER, "wait_sign", null, "blocked"), actor("foreign", "wait_sign", null, "blocked"))));

        assertThatThrownBy(() -> f.processor.process(f.task, f.intent(false)))
                .hasMessageContaining("签署证据需要核实");

        verifyNoInteractions(f.membership);
        verify(f.gateway, never()).cancel(anyString(), anyString());
        verify(f.store, never()).complete(anyString(), anyBoolean());
    }

    @Test void retryingAnUnblockFailurePreservesOwnershipUntilTheProviderConfirmsRecovery() {
        var f = new Fixture();
        f.owned.add(SUPPLIER);
        f.actor(SUPPLIER, "wait_sign", null, "blocked");
        f.actor(BUYER, "wait_sign", null, "blocked");
        doThrow(new BusinessException("解阻暂不可用")).when(f.gateway).unblockActor(TASK, SUPPLIER);

        assertThatThrownBy(() -> f.processor.restoreOwnedBlocks(f.task)).hasMessage("解阻暂不可用");
        assertThat(f.owned).containsExactly(SUPPLIER);
        verify(f.store, never()).forgetOwnedActor(anyString(), anyString());

        doAnswer(invocation -> { f.actor(SUPPLIER, "wait_sign", null, "unblocked"); return null; })
                .when(f.gateway).unblockActor(TASK, SUPPLIER);
        f.processor.restoreOwnedBlocks(f.task);
        assertThat(f.owned).isEmpty();
        assertThat(f.remote.get().actors().get(1).blockStatus()).isEqualTo("blocked");
        verify(f.gateway, never()).unblockActor(TASK, BUYER);
    }

    @Test void completedTasksRetireTemporaryBlocksWithoutChangingTheirSigningResult() {
        var f = new Fixture();
        f.state("task_finished");
        f.actor(SUPPLIER, "signed", "1792994359000", "blocked");
        f.actor(BUYER, "signed", "1792994391000", "blocked");
        f.owned.add(SUPPLIER);

        assertThat(f.processor.process(f.task, f.intent(true))).isEqualTo(SigningCancellationProcessor.Result.SIGNED);

        assertThat(f.task.getProviderStatus()).isEqualTo("task_finished");
        assertThat(f.task.getInitiatorSignStatus()).isEqualTo("signed");
        assertThat(f.task.getCounterpartySignStatus()).isEqualTo("signed");
        assertThat(f.owned).isEmpty();
        verify(f.gateway, never()).cancel(anyString(), anyString());
        verify(f.gateway, never()).unblockActor(anyString(), anyString());
        verifyNoInteractions(f.membership);
    }

    private static FadadaSigningGateway.ActorStatus actor(String id, String signStatus, String time, String block) {
        return new FadadaSigningGateway.ActorStatus(id, signStatus, time, block);
    }

    private static final class Fixture {
        final FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
        final SigningCancellationIntentStore store = mock(SigningCancellationIntentStore.class);
        final MembershipService membership = mock(MembershipService.class);
        final SigningCancellationProcessor processor = new SigningCancellationProcessor(gateway, store, membership);
        final FadadaContractSignTaskDO task = new FadadaContractSignTaskDO();
        final LinkedHashSet<String> owned = new LinkedHashSet<>();
        final AtomicReference<FadadaSigningGateway.TaskStatus> remote = new AtomicReference<>(
                new FadadaSigningGateway.TaskStatus(TASK, "sign_progress", List.of(
                        SigningCancellationProcessorTest.actor(SUPPLIER, "wait_sign", null, "unblocked"),
                        SigningCancellationProcessorTest.actor(BUYER, "wait_sign", null, "unblocked"))));

        Fixture() {
            task.setId(20L); task.setContractId(12L); task.setVersionNo(2); task.setSignTaskId(TASK);
            task.setInitiatorActorId(SUPPLIER); task.setCounterpartyActorId(BUYER);
            task.setInitiatorSignStatus("wait_sign"); task.setCounterpartySignStatus("wait_sign");
            task.setProviderStatus("sign_progress");
            when(gateway.status(TASK)).thenAnswer(invocation -> remote.get());
            when(store.ownedActors(TASK)).thenAnswer(invocation -> List.copyOf(owned));
            doAnswer(invocation -> { owned.add(invocation.getArgument(1)); return null; })
                    .when(store).rememberOwnedActor(eq(TASK), anyString());
            doAnswer(invocation -> { owned.remove(invocation.<String>getArgument(1)); return null; })
                    .when(store).forgetOwnedActor(eq(TASK), anyString());
            doAnswer(invocation -> { block(invocation.getArgument(1), "blocked"); return null; })
                    .when(gateway).blockActor(eq(TASK), anyString());
            doAnswer(invocation -> { block(invocation.getArgument(1), "unblocked"); return null; })
                    .when(gateway).unblockActor(eq(TASK), anyString());
            doAnswer(invocation -> { state("task_terminated"); return null; })
                    .when(gateway).cancel(eq(TASK), anyString());
        }

        SigningCancellationIntentStore.Intent intent(boolean automatic) {
            return new SigningCancellationIntentStore.Intent(TASK, 12L, 2, automatic,
                    automatic ? SigningCancellationProcessor.TIMEOUT_REASON : "发起方撤回合同", List.copyOf(owned));
        }

        void state(String status) {
            var current = remote.get();
            remote.set(new FadadaSigningGateway.TaskStatus(current.signTaskId(), status, current.actors()));
        }

        void block(String id, String block) {
            var current = remote.get().actors().stream().filter(value -> id.equals(value.actorId())).findFirst().orElseThrow();
            actor(id, current.signStatus(), current.signTime(), block);
        }

        void actor(String id, String signStatus, String time, String block) {
            var current = remote.get();
            remote.set(new FadadaSigningGateway.TaskStatus(current.signTaskId(), current.status(), current.actors().stream()
                    .map(value -> id.equals(value.actorId())
                            ? SigningCancellationProcessorTest.actor(id, signStatus, time, block) : value).toList()));
        }
    }
}
