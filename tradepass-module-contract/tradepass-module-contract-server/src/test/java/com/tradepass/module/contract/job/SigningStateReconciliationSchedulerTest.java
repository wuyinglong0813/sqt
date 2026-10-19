package com.tradepass.module.contract.job;

import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.service.signing.FadadaContractSigningService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SigningStateReconciliationSchedulerTest {
    @Test void missedCallbacksAreQueriedAndOneProviderFailureDoesNotStarveOtherContracts() {
        var tasks = mock(FadadaContractSignTaskMapper.class);
        var signing = mock(FadadaContractSigningService.class);
        var properties = new FadadaProperties(); properties.setEnabled(true);
        var one = task(1L, "first"); var two = task(2L, "second"); var claimedElsewhere = task(3L, "other");
        when(tasks.findDueReconciliation(any(), any(), eq(20))).thenReturn(List.of(one, two, claimedElsewhere));
        when(tasks.claimReconciliation(eq(1L), any(), any())).thenReturn(1);
        when(tasks.claimReconciliation(eq(2L), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("provider temporarily unavailable")).when(signing).syncBySignTaskId("first");
        new SigningStateReconciliationScheduler(tasks, signing, properties).reconcile();
        verify(signing).syncBySignTaskId("first"); verify(signing).syncBySignTaskId("second");
        verify(signing, never()).syncBySignTaskId("other");
    }

    @Test void disabledProviderNeverStartsThePollingLoop() {
        var tasks = mock(FadadaContractSignTaskMapper.class); var signing = mock(FadadaContractSigningService.class);
        new SigningStateReconciliationScheduler(tasks, signing, new FadadaProperties()).reconcile();
        verifyNoInteractions(tasks, signing);
    }

    private FadadaContractSignTaskDO task(Long id, String remote) {
        var task = new FadadaContractSignTaskDO(); task.setId(id); task.setSignTaskId(remote);
        task.setUpdatedAt(LocalDateTime.now().minusDays(1)); return task;
    }
}
