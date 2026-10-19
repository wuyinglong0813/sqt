package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaUserIdentityDO;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FadadaIdentityReconciliationServiceTest {
    @Test void disabledProviderDoesNotScanOrQueryAccounts() {
        var f = new Fixture(); f.properties.setEnabled(false);
        f.service.reconcile();
        verifyNoInteractions(f.users, f.companies, f.personal, f.corporate);
    }

    @Test void failedQueriesDoNotPreventOtherIdentitiesOrSealWarningsFromRecovering() {
        var f = new Fixture(); AuthContext.clear();
        var first = user(1L); var second = user(2L);
        when(f.users.findForReconciliation(any(), any())).thenReturn(List.of(first, second));
        when(f.users.claimReconciliation(anyLong(), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("temporary failure")).when(f.personal).syncByClientUserId("user-1");
        var company = new FadadaCorpIdentityDO(); company.setId(3L); company.setCompanyId(30L);
        company.setLocalStatus("VERIFIED"); company.setSealSyncWarning("temporarily unavailable");
        when(f.companies.findForReconciliation(any(), any(), any())).thenReturn(List.of(company));
        when(f.companies.claimReconciliation(eq(3L), any(), any())).thenAnswer(call -> {
            LocalDateTime now = call.getArgument(1), before = call.getArgument(2);
            assertThat(Duration.between(before, now)).isEqualTo(Duration.ofMinutes(1));
            return 1;
        });
        f.service.reconcile();
        verify(f.personal).syncByClientUserId("user-1");
        verify(f.personal).syncByClientUserId("user-2");
        verify(f.corporate).sync(30L);
        assertThat(AuthContext.get()).isNull();
    }

    @Test void anotherWorkerClaimingTheSameIdentityPreventsDuplicateProviderQueries() {
        var f = new Fixture();
        when(f.users.findForReconciliation(any(), any())).thenReturn(List.of(user(1L)));
        when(f.users.claimReconciliation(anyLong(), any(), any())).thenReturn(0);
        f.service.reconcile();
        verifyNoInteractions(f.personal, f.corporate);
    }

    private FadadaUserIdentityDO user(Long id) {
        var user = new FadadaUserIdentityDO(); user.setId(id); user.setClientUserId("user-" + id);
        user.setLocalStatus("VERIFIED"); return user;
    }

    private static class Fixture {
        final FadadaProperties properties = new FadadaProperties();
        final FadadaUserIdentityMapper users = mock(FadadaUserIdentityMapper.class);
        final FadadaCorpIdentityMapper companies = mock(FadadaCorpIdentityMapper.class);
        final FadadaPersonalIdentityService personal = mock(FadadaPersonalIdentityService.class);
        final FadadaCompanyService corporate = mock(FadadaCompanyService.class);
        final FadadaIdentityReconciliationService service = new FadadaIdentityReconciliationService(properties, users, companies, personal, corporate);
        Fixture() { properties.setEnabled(true); }
    }
}
