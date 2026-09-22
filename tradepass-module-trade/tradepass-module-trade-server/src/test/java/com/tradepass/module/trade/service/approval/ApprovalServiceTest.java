package com.tradepass.module.trade.service.approval;

import com.tradepass.module.identity.api.permission.AccessControlOperations;

import com.tradepass.framework.common.core.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApprovalServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApprovalService service = new ApprovalServiceImpl(
            jdbc, mock(AccessControlOperations.class));

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void recordsImmutableCompanyResultAndPreservesRejectionReason() {
        service.recordResult(3L, 4L, "INVOICE", 18L, 12L,
                "REJECTED", "发票已被驳回", "对方已驳回发票 invoice.pdf", "金额不一致");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), values.capture());
        assertThat(sql.getValue()).contains("approval_result_notification", "VALUES");
        assertThat(sql.getValue()).doesNotContain("ON DUPLICATE KEY UPDATE");
        assertThat((Long) values.getValue()[0]).isPositive();
        assertThat(java.util.Arrays.copyOfRange(values.getValue(), 1, values.getValue().length)).containsExactly(
                3L, 4L, "INVOICE", 18L, 12L, "REJECTED",
                "发票已被驳回", "对方已驳回发票 invoice.pdf", "金额不一致");
    }

    @Test
    void marksOnlyCurrentCompanyResultAsRead() {
        AuthContext.set(7L, 3L);

        service.markResultRead(22L);

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), values.capture());
        assertThat(values.getValue()).containsExactly(22L, 3L);
    }
}
