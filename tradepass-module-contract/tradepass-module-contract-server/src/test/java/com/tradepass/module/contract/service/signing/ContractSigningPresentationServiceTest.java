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
        var contract = new TradeContractDO(); contract.setId(12L); contract.setVersionNo(2); contract.setStatus("PENDING");
        var old = task(1, "expired"); var current = task(2, "task_finished");
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(old, current));
        var service = new ContractSigningPresentationService(tasks);
        assertThat(service.statuses(List.of(contract)).get(12L).text()).isEqualTo("签署结果确认中");
        current.setProviderStatus("expired");
        var stopped = service.statuses(List.of(contract)).get(12L);
        assertThat(stopped.status()).isEqualTo("expired"); assertThat(stopped.text()).isEqualTo("签署已过期");
        assertThat(contract.getStatus()).isEqualTo("PENDING");
    }

    private FadadaContractSignTaskDO task(int version, String status) {
        var task = new FadadaContractSignTaskDO(); task.setContractId(12L); task.setVersionNo(version);
        task.setProviderStatus(status); return task;
    }
}
