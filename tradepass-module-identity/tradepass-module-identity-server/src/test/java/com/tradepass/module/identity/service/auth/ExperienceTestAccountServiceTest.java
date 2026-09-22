package com.tradepass.module.identity.service.auth;

import com.tradepass.module.identity.service.company.TenantBootstrapService;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.counterparty.CounterpartyRelationEntityDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperienceTestAccountServiceTest {
    private CompanyMapper companyMapper;
    private CompanyMemberMapper companyMemberMapper;
    private CounterpartyRelationMapper relationMapper;
    private TenantBootstrapService tenantBootstrapService;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(CompanyDO.class, CompanyMemberDO.class, CounterpartyRelationEntityDO.class);
        companyMapper = mock(CompanyMapper.class);
        companyMemberMapper = mock(CompanyMemberMapper.class);
        relationMapper = mock(CounterpartyRelationMapper.class);
        tenantBootstrapService = mock(TenantBootstrapService.class);
    }

    @Test
    void ignoresAccountsWhenDisabledOrPhoneIsNotConfigured() {
        SysUserDO user = user(31L);

        assertThat(service(false).provisionIfConfigured(user, "15632287507")).isNull();
        assertThat(service(true).provisionIfConfigured(user, "13900000000")).isNull();

        verify(companyMapper, never()).selectOne(any(Wrapper.class));
        verify(companyMemberMapper, never()).insert(any(CompanyMemberDO.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void provisionsHebeiAccountWithLegalAccessTemplatesAndCounterparty() {
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            CompanyDO company = invocation.getArgument(0);
            company.setId("91130100MA00000001".equals(company.getCreditCode()) ? 101L : 202L);
            return 1;
        }).when(companyMapper).insert(any(CompanyDO.class));
        when(companyMemberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(relationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        Long assignedCompanyId = service(true).provisionIfConfigured(user(31L), "15632287507");

        assertThat(assignedCompanyId).isEqualTo(101L);
        ArgumentCaptor<CompanyMemberDO> memberCaptor = ArgumentCaptor.forClass(CompanyMemberDO.class);
        verify(companyMemberMapper).insert(memberCaptor.capture());
        CompanyMemberDO member = memberCaptor.getValue();
        assertThat(member.getCompanyId()).isEqualTo(101L);
        assertThat(member.getUserId()).isEqualTo(31L);
        assertThat(member.getRoleCode()).isEqualTo("LEGAL");
        assertThat(member.getStatus()).isEqualTo("ACTIVE");
        assertThat(member.getIsLegalPerson()).isTrue();

        verify(tenantBootstrapService).initialize(101L, 31L);
        verify(tenantBootstrapService).initialize(202L, 31L);

        ArgumentCaptor<CounterpartyRelationEntityDO> relationCaptor =
                ArgumentCaptor.forClass(CounterpartyRelationEntityDO.class);
        verify(relationMapper, times(2)).insert(relationCaptor.capture());
        List<CounterpartyRelationEntityDO> relations = relationCaptor.getAllValues();
        assertThat(relations).extracting(CounterpartyRelationEntityDO::getCompanyId)
                .containsExactly(101L, 202L);
        assertThat(relations).extracting(CounterpartyRelationEntityDO::getCounterpartyCompanyId)
                .containsExactly(202L, 101L);
        assertThat(relations).extracting(CounterpartyRelationEntityDO::getStatus)
                .containsOnly("ACTIVE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignsSecondPhoneToShanghaiCompany() {
        CompanyDO hebei = company(101L, "河北光屿行贸易有限公司", "91130100MA00000001");
        CompanyDO shanghai = company(202L, "上海远航进出口有限公司", "91310000MA00000002");
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(hebei, shanghai);
        when(companyMemberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(relationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        Long assignedCompanyId = service(true).provisionIfConfigured(user(41L), "19802166615");

        assertThat(assignedCompanyId).isEqualTo(202L);
        ArgumentCaptor<CompanyMemberDO> memberCaptor = ArgumentCaptor.forClass(CompanyMemberDO.class);
        verify(companyMemberMapper).insert(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getCompanyId()).isEqualTo(202L);
        assertThat(memberCaptor.getValue().getUserId()).isEqualTo(41L);
        assertThat(memberCaptor.getValue().getRoleCode()).isEqualTo("LEGAL");
    }

    private ExperienceTestAccountService service(boolean enabled) {
        return new ExperienceTestAccountServiceImpl(companyMapper, companyMemberMapper, relationMapper,
                tenantBootstrapService, enabled);
    }

    private SysUserDO user(long id) {
        SysUserDO user = new SysUserDO();
        user.setId(id);
        return user;
    }

    private CompanyDO company(long id, String name, String creditCode) {
        CompanyDO company = new CompanyDO();
        company.setId(id);
        company.setName(name);
        company.setCreditCode(creditCode);
        return company;
    }
}
