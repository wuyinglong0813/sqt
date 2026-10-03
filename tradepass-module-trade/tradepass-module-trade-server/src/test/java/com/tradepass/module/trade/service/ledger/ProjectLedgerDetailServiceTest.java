package com.tradepass.module.trade.service.ledger;

import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectLedgerDetailServiceTest {
    @AfterEach void clearContext() { AuthContext.clear(); }

    @Test void restrictsLedgerToAssignedActivePartyContractsAndRejectsAnotherCompanysProject() {
        AuthContext.set(8L, 4L);
        var jdbc = mock(JdbcTemplate.class);
        var access = mock(AccessControlOperations.class);
        var directory = mock(ContractDirectoryOperations.class);
        var identity = mock(IdentityDirectoryOperations.class);
        var reconciliation = mock(ReconciliationAccountOperations.class);
        var service = new ProjectLedgerServiceImpl(jdbc, access, mock(AuditLogService.class));
        ReflectionTestUtils.setField(service, "contractDirectory", directory);
        ReflectionTestUtils.setField(service, "identityDirectory", identity);
        ReflectionTestUtils.setField(service, "reconciliationAccounts", reconciliation);
        doReturn(List.of(new LinkedHashMap<>(Map.of("id", 51L, "name", "学校改造项目", "projectNo", "XM-51"))))
                .when(jdbc).query(startsWith("SELECT * FROM project_ledger"), any(RowMapper.class), any(Object[].class));
        doReturn(List.of(new Long[]{51L, 12L}, new Long[]{51L, 13L}))
                .when(jdbc).query(startsWith("SELECT project_id, contract_id"), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForList(contains("WHERE company_id = ? AND project_id = ?"), eq(Long.class), eq(4L), eq(51L)))
                .thenReturn(List.of(12L, 13L));
        TradeContractRespDTO active = contract(12L, 4L, "ACTIVE");
        TradeContractRespDTO inactive = contract(13L, 4L, "ABOLISHED");
        when(directory.contractsByIds(anyList())).thenReturn(List.of(active, inactive, contract(99L, 7L, "ACTIVE")));
        when(identity.companyNames(anyList())).thenReturn(Map.of(4L, "当前企业", 7L, "其他企业"));
        when(reconciliation.projectLedgerEntries(List.of(12L))).thenReturn(List.of());

        var detail = service.ledgerDetail(51L);

        verify(access).requireManager(4L);
        verify(reconciliation).projectLedgerEntries(List.of(12L));
        assertThat(detail).containsEntry("contractCount", 1).containsEntry("excludedContractCount", 1);
        doReturn(List.of()).when(jdbc).query(startsWith("SELECT * FROM project_ledger"), any(RowMapper.class), any(Object[].class));
        assertThatThrownBy(() -> service.ledgerDetail(99L)).hasMessage("项目不存在");
        verifyNoMoreInteractions(reconciliation);
    }

    private static TradeContractRespDTO contract(long id, long companyId, String status) {
        var contract = new TradeContractRespDTO();
        contract.setId(id); contract.setCompanyId(companyId); contract.setCounterpartyCompanyId(9L);
        contract.setStatus(status); contract.setDirection("PURCHASE"); contract.setCounterpartyName("供应商");
        return contract;
    }
}
