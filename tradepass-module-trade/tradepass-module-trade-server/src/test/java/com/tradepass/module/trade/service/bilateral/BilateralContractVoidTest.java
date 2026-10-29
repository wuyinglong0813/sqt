package com.tradepass.module.trade.service.bilateral;

import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperations;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.api.state.ContractStateOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import com.tradepass.module.trade.service.approval.ApprovalService;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BilateralContractVoidTest {
    @AfterEach void clear() { AuthContext.clear(); }

    @Test void approvingElectronicContractVoidImmediatelyChangesLocalStatusAndRecordsConsent() throws Exception {
        var f = new Fixture();
        AuthContext.set(8L, 4L);

        var result = f.service.decide(21L, "APPROVE", "");

        assertThat(f.contract.getStatus()).isEqualTo("VOIDED");
        assertThat(result.get("status")).isEqualTo("APPROVED");
        assertThat(result.get("canReview")).isEqualTo(false);
        verify(f.state).changeActiveStatus(12L, "VOIDED");
        verify(f.state, never()).electronicTaskCount(anyLong());
        verifyNoInteractions(f.recovery);
        verify(f.access).requirePermission(4L, "contract_sign");
        verify(f.approvals).recordResult(3L, 4L, "BILATERAL_ACTION", 21L, 12L,
                "APPROVED", "作废申请已同意", "对方已同意作废申请", null);
        verify(f.approvals).recordResult(4L, 3L, "BILATERAL_ACTION", 21L, 12L,
                "APPROVED", "已同意作废申请", "我方已同意作废申请", null);
        verify(f.audit).log(4L, "BILATERAL_ACTION", 21L, "APPROVE", "同意作废申请");
        assertThat(f.service.isContractReadOnly(f.contract)).isTrue();
        assertThatThrownBy(() -> f.service.requireContractMutable(f.contract)).hasMessageContaining("合同已结束或作废");
    }

    @Test void localVoidFallbackAlsoCompletesWithoutReadingProviderTasks() throws Exception {
        var f = new Fixture();
        ReflectionTestUtils.setField(f.service, "contractState", null);
        AuthContext.set(8L, 4L);

        assertThat(f.service.decide(21L, "APPROVE", "").get("status")).isEqualTo("APPROVED");

        verify(f.jdbc).update(contains("UPDATE trade_contract SET status"), eq("VOIDED"), eq(12L));
        verify(f.jdbc, never()).queryForObject(contains("fadada_contract_sign_task"), eq(Long.class), anyLong());
        verifyNoInteractions(f.recovery);
    }

    @Test void onlyTheOtherCompanyCanApproveAndBothSidesNeedContractPermission() throws Exception {
        var f = new Fixture();
        AuthContext.set(7L, 3L);
        assertThatThrownBy(() -> f.service.decide(21L, "APPROVE", "")).hasMessageContaining("仅对方企业");
        verify(f.state, never()).changeActiveStatus(anyLong(), anyString());

        AuthContext.set(8L, 4L);
        doThrow(new IllegalStateException("无合同权限")).when(f.access).requirePermission(4L, "contract_sign");
        assertThatThrownBy(() -> f.service.decide(21L, "APPROVE", "")).hasMessageContaining("无合同权限");
        verify(f.state, never()).changeActiveStatus(anyLong(), anyString());
        verifyNoInteractions(f.recovery);
    }

    @Test void approvalRechecksUnclearedBusinessDocumentsBeforeVoiding() throws Exception {
        var f = new Fixture();
        f.effectiveDocuments = 1;
        AuthContext.set(8L, 4L);

        assertThatThrownBy(() -> f.service.decide(21L, "APPROVE", "")).hasMessageContaining("未清理的单据");

        assertThat(f.actionStatus).isEqualTo("PENDING");
        verify(f.state, never()).changeActiveStatus(anyLong(), anyString());
        verifyNoInteractions(f.approvals, f.recovery);
    }

    @Test void requestStillChecksDocumentsAndTheRequestingCompanyPermission() throws Exception {
        var f = new Fixture();
        f.effectiveDocuments = 1;
        AuthContext.set(7L, 3L);

        assertThatThrownBy(() -> f.service.request("CONTRACT", 12L, "VOID", "双方协商", false))
                .hasMessageContaining("未清理的单据");

        verify(f.access).requirePermission(3L, "contract_sign");
        verify(f.jdbc, never()).update(contains("INSERT INTO bilateral_action_request"), any(Object[].class));
    }

    @Test void oldApprovedElectronicVoidHistoryDoesNotCreateAnExtraFulfillmentLock() throws Exception {
        var f = new Fixture();
        f.approvedVoidHistory = true;
        AuthContext.set(7L, 3L);

        assertThat(f.service.isContractReadOnly(f.contract)).isFalse();
        assertThatCode(() -> f.service.requireContractMutable(f.contract)).doesNotThrowAnyException();
        assertThat(f.service.request("CONTRACT", 12L, "VOID", "本地作废", false).get("actionType"))
                .isEqualTo("VOID");

        verify(f.state, never()).electronicTaskCount(anyLong());
        verifyNoInteractions(f.recovery);
    }

    @Test void pendingApplicationStillLocksFulfillmentAndCanBeWithdrawnByItsRequester() throws Exception {
        var f = new Fixture();
        f.pendingApplications = 1;
        AuthContext.set(7L, 3L);
        assertThat(f.service.isContractReadOnly(f.contract)).isTrue();
        assertThatThrownBy(() -> f.service.requireContractMutable(f.contract)).hasMessageContaining("等待双方处理");

        assertThat(f.service.cancel(21L)).isEqualTo("申请已撤回");

        assertThat(f.actionStatus).isEqualTo("CANCELLED");
        assertThat(f.service.isContractReadOnly(f.contract)).isFalse();
        verify(f.state, never()).changeActiveStatus(anyLong(), anyString());
        verifyNoInteractions(f.recovery);
    }

    @Test void newRecoveryRequestsAreRejectedAndHistoricalRecoveryCannotBeApproved() throws Exception {
        var f = new Fixture();
        AuthContext.set(7L, 3L);
        assertThatThrownBy(() -> f.service.request("CONTRACT", 12L, "RESUME", "继续履约", false))
                .hasMessageContaining("不支持恢复履约");
        f.actionType = "RESUME";
        AuthContext.set(8L, 4L);
        assertThatThrownBy(() -> f.service.decide(21L, "APPROVE", ""))
                .hasMessageContaining("拒绝或撤回该历史恢复申请");
        assertThat(f.actionStatus).isEqualTo("PENDING");
        verify(f.state, never()).changeActiveStatus(anyLong(), anyString());
        verifyNoInteractions(f.recovery);

        assertThat(f.service.decide(21L, "REJECT", "作废后不再恢复").get("status")).isEqualTo("REJECTED");
    }

    private static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final ContractReader contracts = mock(ContractReader.class);
        final ContractStateOperations state = mock(ContractStateOperations.class);
        final AccessControlOperations access = mock(AccessControlOperations.class);
        final AuditLogService audit = mock(AuditLogService.class);
        final ApprovalService approvals = mock(ApprovalService.class);
        final ContractAbolishRecoveryOperations recovery = mock(ContractAbolishRecoveryOperations.class);
        final TradeContractRespDTO contract = new TradeContractRespDTO();
        final BilateralActionServiceImpl service = new BilateralActionServiceImpl(jdbc, contracts, access, audit,
                mock(ReconciliationAccountOperations.class), mock(SalesOrderInventoryService.class), approvals);
        String actionType = "VOID";
        String actionStatus = "PENDING";
        long pendingApplications;
        long effectiveDocuments;
        boolean approvedVoidHistory;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Fixture() throws Exception {
            contract.setId(12L); contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L);
            contract.setStatus("ACTIVE");
            when(contracts.selectById(12L)).thenReturn(contract);
            when(contracts.selectByIdForUpdate(12L)).thenReturn(contract);
            ReflectionTestUtils.setField(service, "contractState", state);
            service.setAbolishRecoveryService(recovery);
            when(state.changeActiveStatus(eq(12L), anyString())).thenAnswer(call -> {
                contract.setStatus(call.getArgument(1));
                return 1;
            });
            when(jdbc.queryForObject(anyString(), eq(Long.class), eq(12L))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                if (sql.contains("FROM bilateral_action_request") && sql.contains("status = 'APPROVED'")) {
                    return approvedVoidHistory ? 1L : 0L;
                }
                if (sql.contains("FROM business_document")) return effectiveDocuments;
                return sql.contains("FROM bilateral_action_request") ? pendingApplications : 0L;
            });
            when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                if (sql.contains("SET status = 'APPROVED'")) actionStatus = "APPROVED";
                if (sql.contains("SET status = 'REJECTED'")) actionStatus = "REJECTED";
                if (sql.contains("SET status = 'CANCELLED'")) {
                    actionStatus = "CANCELLED";
                    pendingApplications = 0;
                }
                return 1;
            });
            when(jdbc.query(contains("FROM bilateral_action_request WHERE id = ?"), any(RowMapper.class), anyLong()))
                    .thenAnswer(call -> {
                        ResultSet row = mock(ResultSet.class);
                        when(row.getLong("id")).thenReturn((Long) call.getArgument(2));
                        when(row.getLong("contract_id")).thenReturn(12L);
                        when(row.getString("biz_type")).thenReturn("CONTRACT");
                        when(row.getLong("biz_id")).thenReturn(12L);
                        when(row.getString("action_type")).thenReturn(actionType);
                        when(row.getLong("requester_company_id")).thenReturn(3L);
                        when(row.getLong("requester_user_id")).thenReturn(7L);
                        when(row.getLong("approver_company_id")).thenReturn(4L);
                        when(row.getString("status")).thenReturn(actionStatus);
                        when(row.getString("reason")).thenReturn("双方协商");
                        when(row.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 10, 23, 12, 0)));
                        return List.of(((RowMapper) call.getArgument(1)).mapRow(row, 0));
                    });
        }
    }
}
