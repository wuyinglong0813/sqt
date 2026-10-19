package com.tradepass.module.contract.service.membership;

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

class MembershipPreparationReleaseTest {
    @Test void provenPreCreateFailureReleasesExactlyOneReservedQuotaAndAllowsRetry() {
        var f = new Fixture("RESERVED", null);
        f.service.releaseBeforeTaskCreation(12L);
        verify(f.jdbc).update(contains("reserved_amount=reserved_amount-1"), eq("pool"), eq("subject"));
        verify(f.jdbc).update(contains("status='RELEASED'"), eq(12L));
        verify(f.transactions).getTransaction(argThat(def -> def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @ParameterizedTest @ValueSource(strings = {"UNCERTAIN", "CONSUMED"})
    void aTaskThatMayHaveBeenCreatedCannotUseThePreparationReleasePath(String state) {
        var f = new Fixture(state, null);
        assertThatThrownBy(() -> f.service.releaseBeforeTaskCreation(12L)).hasMessageContaining("不能按文件准备失败释放");
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void evenAReservedRecordWithATaskIdCannotBeReleased() {
        var f = new Fixture("RESERVED", "remote-task");
        assertThatThrownBy(() -> f.service.releaseBeforeTaskCreation(12L)).hasMessageContaining("不能按文件准备失败释放");
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void duplicatePreparationReleaseDoesNotDecrementQuotaAgain() {
        var f = new Fixture("RELEASED", null);
        f.service.releaseBeforeTaskCreation(12L);
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    private static class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final MembershipPolicyStore policies = mock(MembershipPolicyStore.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final MembershipService service;
        Fixture(String state, String taskId) {
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            Map<String,Object> row = new HashMap<>(); row.put("scope_key", "pool"); row.put("subject_key", "subject");
            row.put("status", state); row.put("sign_task_id", taskId);
            when(jdbc.queryForList(anyString(), eq(12L))).thenReturn(List.of(row));
            when(jdbc.update(contains("reserved_amount=reserved_amount-1"), eq("pool"), eq("subject"))).thenReturn(1);
            when(jdbc.update(contains("status='RELEASED'"), eq(12L))).thenReturn(1);
            service = new MembershipService(jdbc, policies, transactions);
        }
    }
}
