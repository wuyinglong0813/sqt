package com.tradepass.module.trade.service.ledger;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.framework.audit.core.AuditLogService;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.support.TestIds;
import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectLedgerServiceTest {
    private JdbcTemplate jdbc;
    private AccessControlOperations accessControlService;
    private ProjectLedgerService service;

    @BeforeEach
    void setUp() {
        TestIds.use(51L);
        jdbc = mock(JdbcTemplate.class);
        accessControlService = mock(AccessControlOperations.class);
        service = new ProjectLedgerServiceImpl(jdbc, accessControlService, mock(AuditLogService.class));
        AuthContext.set(8L, 4L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
        TestIds.reset();
    }

    @Test
    void createsAnOptionalCompanyProjectWithoutTouchingContracts() {
        Map<String, Object> project = project(51L, "雄安办公楼一期");
        TestIds.use(51L);
        doReturn(List.of(project)).when(jdbc)
                .query(anyString(), any(RowMapper.class), any(Object[].class));

        Map<String, Object> result = service.createProject("", " 雄安办公楼一期 ", " 项目说明 ");

        assertThat(result).containsEntry("id", 51L).containsEntry("purchaseCost", "0.00");
        verify(accessControlService).requireManager(4L);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        assertThat(arguments.getValue()[1]).isEqualTo(4L);
        assertThat(arguments.getValue()[2].toString()).startsWith("XM-");
        assertThat(arguments.getValue()[3]).isEqualTo("雄安办公楼一期");
        assertThat(arguments.getValue()[4]).isEqualTo("项目说明");
    }

    @Test
    void rejectsInvalidAndDuplicateProjectNames() {
        assertThatThrownBy(() -> service.createProject("", "", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目名称不能为空");

        doThrow(new DuplicateKeyException("duplicate")).when(jdbc)
                .update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> service.createProject("XM-1", "重复项目", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessage("项目名称或编号已存在");
    }

    @Test
    void assignsOnlyActivePartyContractsAndKeepsOneProjectPerCompany() {
        Map<String, Object> project = project(51L, "雄安办公楼一期");
        doReturn(List.of(project), List.of(project), List.of())
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        doReturn(12L).when(jdbc)
                .query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        Map<String, Object> result = service.assignContracts(51L, List.of(12L, 12L));

        assertThat(result).containsEntry("id", 51L).containsEntry("contracts", List.of());
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        assertThat(arguments.getValue()).containsExactly(51L, 4L, 51L, 12L, 8L);
    }

    @Test
    void refusesContractsThatAreNotActivePartiesOrAlreadyAssigned() {
        Map<String, Object> project = project(51L, "雄安办公楼一期");
        doReturn(List.of(project)).when(jdbc)
                .query(anyString(), any(RowMapper.class), any(Object[].class));
        doReturn(null).when(jdbc)
                .query(anyString(), any(ResultSetExtractor.class), any(Object[].class));

        assertThatThrownBy(() -> service.assignContracts(51L, List.of(99L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("合同不存在或尚未签署生效");

        doReturn(List.of(project)).when(jdbc)
                .query(anyString(), any(RowMapper.class), any(Object[].class));
        doReturn(12L).when(jdbc)
                .query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        doThrow(new DuplicateKeyException("duplicate")).when(jdbc)
                .update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> service.assignContracts(51L, List.of(12L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("所选合同已划入其他项目");
    }

    @Test
    void aggregateSqlCalculatesPurchaseAndSaleFromTheViewingCompanyPerspective() {
        doReturn(List.of()).when(jdbc)
                .query(anyString(), any(RowMapper.class), any(Object[].class));

        service.listProjects();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), arguments.capture());
        assertThat(sql.getValue())
                .contains("contract.company_id = ? AND UPPER(contract.direction) = 'PURCHASE'")
                .contains("contract.counterparty_company_id = ? AND UPPER(contract.direction) = 'SALE'")
                .contains("contract.status = 'ACTIVE'");
        assertThat(arguments.getValue()).containsExactly(4L, 4L, 4L, 4L, 4L);
    }

    @Test
    void findsContractAssignmentOnlyWithinTheCurrentCompany() {
        doReturn(12L).when(jdbc)
                .query(anyString(), any(ResultSetExtractor.class), any(Object[].class));
        doReturn(List.of(Map.of(
                "assigned", true,
                "projectId", 51L,
                "projectNo", "XM-51",
                "projectName", "雄安办公楼一期"
        ))).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThat(service.contractAssignment(12L))
                .containsEntry("assigned", true)
                .containsEntry("projectId", 51L);
        verify(accessControlService).requireManager(4L);
    }

    private Map<String, Object> project(long id, String name) {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("id", id);
        project.put("name", name);
        project.put("status", "ACTIVE");
        project.put("purchaseCost", "0.00");
        return project;
    }
}
