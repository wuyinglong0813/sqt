package com.tradepass.module.settlement.service.reconciliation;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.api.counterparty.CounterpartyReader;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationEntryDO;
import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationEntryMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProjectLedgerEntriesTest {
    private final ReconciliationEntryMapper mapper = mock(ReconciliationEntryMapper.class);
    private final AccessControlOperations access = mock(AccessControlOperations.class);
    private final ReconciliationAccountService service = new ReconciliationAccountServiceImpl(
            mock(JdbcTemplate.class), mapper, mock(CounterpartyReader.class), access);

    @AfterEach void clearContext() { AuthContext.clear(); }

    @Test void readsOnlyRequestedContractEntriesForTheCurrentCompanyIncludingReversals() {
        AuthContext.set(8L, 4L);
        ReconciliationEntryDO reversal = new ReconciliationEntryDO();
        reversal.setId(99L); reversal.setContractId(12L); reversal.setSourceType("INVOICE_VOID");
        reversal.setBusinessDate(LocalDate.of(2026, 9, 1)); reversal.setAmount(new BigDecimal("-100.00"));
        when(mapper.selectProjectLedgerEntries(4L, List.of(12L))).thenReturn(List.of(reversal));
        var entries = service.projectLedgerEntries(List.of(12L, 12L));
        verify(access).requireManager(4L);
        verify(mapper).selectProjectLedgerEntries(4L, List.of(12L));
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.sourceType()).isEqualTo("INVOICE_VOID");
            assertThat(entry.amount()).isEqualByComparingTo("-100.00");
        });
    }

    @Test void rejectsUnauthorizedAccessAndNeverExpandsAnEmptyProjectToAllEntries() {
        AuthContext.set(8L, 4L);
        assertThat(service.projectLedgerEntries(List.of())).isEmpty();
        verifyNoInteractions(mapper);
        doThrow(new BusinessException("仅管理员可操作")).when(access).requireManager(4L);
        assertThatThrownBy(() -> service.projectLedgerEntries(List.of(12L))).hasMessage("仅管理员可操作");
        verifyNoInteractions(mapper);
    }

    @Test void dynamicMapperBindsTenantAndContractIdsWithoutDroppingTheCompanyPredicate() {
        Configuration configuration = new Configuration();
        configuration.addMapper(ReconciliationEntryMapper.class);
        var statement = configuration.getMappedStatement(ReconciliationEntryMapper.class.getName() + ".selectProjectLedgerEntries");
        var query = statement.getBoundSql(Map.of("companyId", 4L, "contractIds", List.of(12L, 13L)));
        assertThat(query.getSql().replaceAll("\\s+", " "))
                .contains("(company_a_id = ? OR company_b_id = ?) AND contract_id IN ( ? , ? )")
                .contains("ORDER BY business_date, approved_at, id");
        assertThat(query.getParameterMappings()).hasSize(4);
    }
}
