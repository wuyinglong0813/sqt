package com.tradepass.module.identity.service.notice;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.notice.MemberRemovalNoticeDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.notice.MemberRemovalNoticeMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberRemovalNoticeServiceTest {
    private MemberRemovalNoticeMapper mapper;
    private CompanyMapper companyMapper;
    private MemberRemovalNoticeService service;

    @BeforeEach
    void setup() {
        MybatisTestSupport.initialize(MemberRemovalNoticeDO.class);
        mapper = mock(MemberRemovalNoticeMapper.class);
        companyMapper = mock(CompanyMapper.class);
        service = new MemberRemovalNoticeServiceImpl(mapper, companyMapper);
        AuthContext.set(7L, null);
    }

    @AfterEach
    void cleanup() { AuthContext.clear(); }

    @Test
    void recordsRecipientAndCompanyNameAndReturnsUnreadNoticesWithoutCompanyMembership() {
        CompanyDO company = new CompanyDO();
        company.setName("测试企业");
        when(companyMapper.selectById(3L)).thenReturn(company);
        service.recordRemoval(7L, 3L);
        ArgumentCaptor<MemberRemovalNoticeDO> notice = ArgumentCaptor.forClass(MemberRemovalNoticeDO.class);
        verify(mapper).insert(notice.capture());
        assertThat(notice.getValue().getUserId()).isEqualTo(7L);
        assertThat(notice.getValue().getCompanyId()).isEqualTo(3L);
        assertThat(notice.getValue().getCompanyName()).isEqualTo("测试企业");
        assertThat(notice.getValue().getAcknowledgedAt()).isNull();
        notice.getValue().setId(9007199254740993L);
        when(mapper.selectPending(7L)).thenReturn(List.of(notice.getValue()));
        assertThat(service.pending().get(0).id()).isEqualTo("9007199254740993");
        assertThat(service.pending().get(0).companyName()).isEqualTo("测试企业");
        verify(mapper, never()).update(any(Wrapper.class));
    }

    @Test
    void acknowledgementsAreScopedToCurrentUserAndOnlyRequestedNotices() {
        service.acknowledge(List.of("9007199254740993"));
        ArgumentCaptor<Wrapper> captured = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(captured.capture());
        var update = (LambdaUpdateWrapper<MemberRemovalNoticeDO>) captured.getValue();
        assertThat(update.getSqlSegment()).contains("user_id =", "id IN", "acknowledged_at IS NULL");
        assertThat(update.getParamNameValuePairs().values()).contains(7L, 9007199254740993L);
        assertThat(update.getSqlSet()).contains("acknowledged_at=");
        assertThatThrownBy(() -> service.acknowledge(List.of())).hasMessage("请选择有效的通知");
        assertThatThrownBy(() -> service.acknowledge(List.of("invalid"))).hasMessage("通知 ID 格式不正确");
        verify(mapper, times(1)).update(any(Wrapper.class));
    }
}
