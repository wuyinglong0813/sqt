package com.tradepass.module.contract.service.membership;

import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MembershipUnsignedRefundTest {
    @Test void cancelledUnsignedTaskReturnsOneCreditToItsOriginalPoolAndKeepsTheReceipt() {
        var f = new Fixture("CONSUMED", "task-v2");
        f.service.refundCancelledUnsigned(8L, 2, "task-v2");

        var order = inOrder(f.policies, f.jdbc);
        order.verify(f.policies).current(true);
        order.verify(f.jdbc).queryForList(contains("FOR UPDATE"), eq(8L), eq(2));
        order.verify(f.jdbc).update(contains("consumed_amount=consumed_amount-1"), eq("PAID:original"), eq("subject"));
        order.verify(f.jdbc).update(contains("status='REFUNDED'"), eq(12L), eq("task-v2"));
        verify(f.jdbc, never()).update(contains("expires_at="), any(Object[].class));
        verify(f.jdbc, never()).update(contains("sign_task_id=NULL"), any(Object[].class));
        verify(f.transactions).getTransaction(argThat(def ->
                def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test void aRepeatedRefundCannotIncreaseRemainingCreditsAgain() {
        var f = new Fixture("REFUNDED", "task-v2");
        f.service.refundCancelledUnsigned(8L, 2, "task-v2");
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @ParameterizedTest @ValueSource(strings = {"CONSUMED", "REFUNDED"})
    void aDifferentTaskCannotRefundEvenAnAlreadyRefundedVersion(String state) {
        var f = new Fixture(state, "other-task");
        assertThatThrownBy(() -> f.service.refundCancelledUnsigned(8L, 2, "task-v2"))
                .hasMessageContaining("不一致");
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
        verify(f.transactions).rollback(any());
    }

    @ParameterizedTest @ValueSource(strings = {"RESERVED", "UNCERTAIN", "RELEASED", "UNKNOWN"})
    void onlyAConfirmedConsumptionCanBeRefunded(String state) {
        var f = new Fixture(state, "task-v2");
        assertThatThrownBy(() -> f.service.refundCancelledUnsigned(8L, 2, "task-v2"))
                .hasMessageContaining("状态不允许");
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void aLegacyTaskWithoutALocalUsageDoesNotReceiveANewCredit() {
        var f = new Fixture("CONSUMED", "task-v2");
        when(f.jdbc.queryForList(anyString(), eq(8L), eq(2))).thenReturn(List.of());
        f.service.refundCancelledUnsigned(8L, 2, "task-v2");
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void inconsistentPoolCountersCannotBeMarkedRefunded() {
        var f = new Fixture("CONSUMED", "task-v2");
        when(f.jdbc.update(contains("consumed_amount=consumed_amount-1"), eq("PAID:original"), eq("subject")))
                .thenReturn(0);
        assertThatThrownBy(() -> f.service.refundCancelledUnsigned(8L, 2, "task-v2"))
                .hasMessageContaining("额度记录不一致");
        verify(f.jdbc, never()).update(contains("status='REFUNDED'"), any(Object[].class));
        verify(f.transactions).rollback(any());
    }

    @Test void failureToRecordTheRefundRollsBackTheCreditChange() {
        var f = new Fixture("CONSUMED", "task-v2");
        when(f.jdbc.update(contains("status='REFUNDED'"), eq(12L), eq("task-v2"))).thenReturn(0);
        assertThatThrownBy(() -> f.service.refundCancelledUnsigned(8L, 2, "task-v2"))
                .hasMessageContaining("状态已变化");
        verify(f.transactions).rollback(any());
    }

    @Test void anOldCreationReceiptCannotConsumeTheRefundedVersionAgain() {
        var f = new Fixture("REFUNDED", "task-v2");
        when(f.jdbc.queryForList(anyString(), eq(12L))).thenReturn(List.of(f.row));
        assertThatThrownBy(() -> f.service.confirm(12L, new FadadaSigningGateway.CreatedTask("task-v2", "file", "doc")))
                .hasMessageContaining("状态已变化");
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void refundedVersionMustBeResubmittedInsteadOfCreatingAnotherTask() {
        var f = new Fixture("REFUNDED", "task-v2");
        var service = spy(f.service);
        doReturn(new MembershipService.Reservation(12L, "REFUNDED", false, "task-v2", "file", "doc", "{}", "hash"))
                .when(service).existing(8L, 2);
        assertThatThrownBy(() -> service.reserve(new CompanyRespDTO(), 8L, 2, "{}", "hash"))
                .hasMessageContaining("该版本签署已撤回并返还额度");
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void incompleteRefundIdentityCannotTouchTheLedger() {
        var f = new Fixture("CONSUMED", "task-v2");
        assertThatThrownBy(() -> f.service.refundCancelledUnsigned(8L, 2, " "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(f.policies, f.jdbc, f.transactions);
    }

    private static class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final MembershipPolicyStore policies = mock(MembershipPolicyStore.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final Map<String, Object> row = new HashMap<>();
        final MembershipService service;

        Fixture(String state, String taskId) {
            row.put("id", 12L); row.put("scope_key", "PAID:original"); row.put("subject_key", "subject");
            row.put("status", state); row.put("sign_task_id", taskId);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(jdbc.queryForList(anyString(), eq(8L), eq(2))).thenReturn(List.of(row));
            when(jdbc.update(contains("consumed_amount=consumed_amount-1"), eq("PAID:original"), eq("subject")))
                    .thenReturn(1);
            when(jdbc.update(contains("status='REFUNDED'"), eq(12L), eq("task-v2"))).thenReturn(1);
            service = new MembershipService(jdbc, policies, transactions);
        }
    }
}
