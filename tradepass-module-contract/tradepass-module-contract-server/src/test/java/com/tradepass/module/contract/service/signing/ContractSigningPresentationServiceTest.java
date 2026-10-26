package com.tradepass.module.contract.service.signing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ContractSigningPresentationServiceTest {
    @Test void showsOnlyTheCurrentVersionAndDoesNotPretendAnUnarchivedTaskIsArchived() {
        MybatisTestSupport.initialize(FadadaContractSignTaskDO.class);
        var tasks = mock(FadadaContractSignTaskMapper.class);
        var contract = new TradeContractDO(); contract.setId(12L); contract.setCompanyId(3L);
        contract.setVersionNo(2); contract.setStatus("PENDING");
        var old = task(1, "expired"); var current = task(2, "task_finished");
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(old, current));
        var service = new ContractSigningPresentationService(tasks);
        assertThat(service.statuses(List.of(contract), 3L).get(12L).text()).isEqualTo("签署结果确认中");
        current.setProviderStatus("expired");
        var stopped = service.statuses(List.of(contract), 3L).get(12L);
        assertThat(stopped.status()).isEqualTo("expired"); assertThat(stopped.text()).isEqualTo("签署已过期");
        assertThat(contract.getStatus()).isEqualTo("PENDING");
    }

    @Test void completedPartySeesWaitingForTheOtherPartyWhileTheOtherPartySeesItsOwnTodo() {
        MybatisTestSupport.initialize(FadadaContractSignTaskDO.class);
        var tasks = mock(FadadaContractSignTaskMapper.class);
        var contract = new TradeContractDO(); contract.setId(12L); contract.setCompanyId(3L);
        contract.setVersionNo(2); contract.setStatus("PENDING");
        var current = task(2, "sign_progress");
        current.setInitiatorSignStatus("signed"); current.setCounterpartySignStatus("wait_sign");
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(current));
        var service = new ContractSigningPresentationService(tasks);

        assertThat(service.statuses(List.of(contract), 3L).get(12L))
                .isEqualTo(new ContractSigningPresentationService.Progress("sign_progress", "我方已签署，等待对方签署"));
        assertThat(service.statuses(List.of(contract), 4L).get(12L).text()).isEqualTo("待我方签署");
        current.setCounterpartySignStatus("signed");
        assertThat(service.statuses(List.of(contract), 3L).get(12L).text()).isEqualTo("双方已签署，正在归档");
        assertThat(contract.getStatus()).isEqualTo("PENDING");

        current.setProviderStatus("expired");
        assertThat(service.statuses(List.of(contract), 3L).get(12L).text()).isEqualTo("签署已过期");
    }

    @Test void anUnsignedCurrentTaskDoesNotReuseTheOldVersionsCompletedParty() {
        MybatisTestSupport.initialize(FadadaContractSignTaskDO.class);
        var tasks = mock(FadadaContractSignTaskMapper.class);
        var contract = new TradeContractDO(); contract.setId(12L); contract.setCompanyId(3L);
        contract.setVersionNo(2); contract.setStatus("PENDING");
        var old = task(1, "task_finished"); old.setInitiatorSignStatus("signed"); old.setCounterpartySignStatus("signed");
        var current = task(2, "sign_progress");
        current.setInitiatorSignStatus("wait_sign"); current.setCounterpartySignStatus("wait_sign");
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(old, current));
        assertThat(new ContractSigningPresentationService(tasks).statuses(List.of(contract), 3L).get(12L).text())
                .isEqualTo("签署中");
    }

    private FadadaContractSignTaskDO task(int version, String status) {
        var task = new FadadaContractSignTaskDO(); task.setContractId(12L); task.setVersionNo(version);
        task.setProviderStatus(status); return task;
    }
}
