package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaSigningMemberSyncMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FadadaSigningMemberSyncServiceTest {
    @AfterEach void clearContext() { AuthContext.clear(); TransactionSynchronizationManager.clear(); }

    @Test void workIsPersistedInANewTransactionOnlyAfterTheLocalChangeCommits() {
        var f = new Fixture();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        f.service.request(new SigningMemberSyncRequested(3L, 9L, 7L, "MEMBER_APPROVED"));
        verifyNoInteractions(f.queue, f.authorization);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(f.queue).enqueue(3L, 9L, 7L, "MEMBER_APPROVED");
        verify(f.transactions).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verifyNoInteractions(f.authorization);
    }

    @Test void aRolledBackLocalChangeDoesNotCreateProviderWork() {
        var f = new Fixture();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        f.service.request(new SigningMemberSyncRequested(3L, 9L, 7L, "MEMBER_APPROVED"));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(f.queue, f.authorization, f.transactions);
    }

    @Test void queueFailureCannotUndoAnAlreadySuccessfulLocalApprovalOrIdentity() {
        var f = new Fixture();
        doThrow(new IllegalStateException("database unavailable")).when(f.queue).enqueue(any(), any(), any(), any());
        assertThatCode(() -> f.service.request(new SigningMemberSyncRequested(null, 9L, null, "PERSONAL_IDENTITY_VERIFIED")))
                .doesNotThrowAnyException();
        verifyNoInteractions(f.authorization);
    }

    @Test void aDurableClaimMustBeWonBeforeAnyProviderReconciliation() {
        var f = new Fixture();
        when(f.queue.due()).thenReturn(List.of(new FadadaSigningMemberSyncMapper.Candidate(3L, 9L, 7L)));
        f.service.reconcile();
        verify(f.queue).backfill();
        verify(f.queue).recheckLegacyReady();
        verifyNoInteractions(f.authorization);
        verify(f.queue, never()).finish(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyInt());
    }

    @Test void missingOrganizationConsentStaysADurablePendingActionInsteadOfReady() {
        var f = new Fixture(); f.claimed();
        doThrow(new BusinessException(46101, "组织数据授权待补齐"))
                .when(f.authorization).reconcileLocalSigningAuthorization(3L, 9L);
        f.service.reconcile();
        verify(f.queue).finish(eq(3L), eq(9L), anyString(), eq("WAITING_COMPANY_AUTHORIZATION"),
                eq(46101), eq("组织数据授权待补齐"), eq(600));
        assertThat(AuthContext.get()).isNull();
    }

    @Test void anUnactivatedInvitationIsNotReportedAsReady() {
        var f = new Fixture(); f.claimed();
        doThrow(new BusinessException(46102, "请本人确认加入"))
                .when(f.authorization).reconcileLocalSigningAuthorization(3L, 9L);
        f.service.reconcile();
        verify(f.queue).finish(eq(3L), eq(9L), anyString(), eq("ACTIVATION_REQUIRED"),
                eq(46102), eq("请本人确认加入"), eq(600));
    }

    @Test void anEffectiveGrantCompletesOnlyTheExplicitTargetWithoutImpersonatingTheActor() {
        var f = new Fixture(); f.claimed();
        f.service.reconcile();
        verify(f.authorization).reconcileLocalSigningAuthorization(3L, 9L);
        verify(f.queue).finish(eq(3L), eq(9L), anyString(), eq("SIGNING_READY"), eq(0), eq(""), eq(86400));
        assertThat(AuthContext.get()).isNull();
    }

    @Test void anActivatedMemberWithoutTheSealGrantIsPersistedAsUndelivered() {
        var f = new Fixture(); f.claimed();
        doThrow(new BusinessException(46104, "用印授权待确认"))
                .when(f.authorization).reconcileLocalSigningAuthorization(3L, 9L);
        f.service.reconcile();
        verify(f.queue).finish(eq(3L), eq(9L), anyString(), eq("WAITING_SEAL_AUTHORIZATION"),
                eq(46104), eq("用印授权待确认"), eq(600));
        assertThat(AuthContext.get()).isNull();
    }

    @Test void aMissingSigningSealRemainsAnExplicitCompanyAction() {
        var f = new Fixture(); f.claimed();
        doThrow(new BusinessException(46107, "待设置签署印章"))
                .when(f.authorization).reconcileLocalSigningAuthorization(3L, 9L);
        f.service.reconcile();
        verify(f.queue).finish(eq(3L), eq(9L), anyString(), eq("WAITING_SEAL_CONFIGURATION"),
                eq(46107), eq("待设置签署印章"), eq(600));
    }

    private static class Fixture {
        final FadadaSigningMemberSyncMapper queue = mock(FadadaSigningMemberSyncMapper.class);
        final FadadaSigningAuthorizationService authorization = mock(FadadaSigningAuthorizationService.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final FadadaSigningMemberSyncService service;
        Fixture() {
            var properties = new FadadaProperties(); properties.setEnabled(true);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            service = new FadadaSigningMemberSyncService(queue, authorization, properties, transactions);
        }
        void claimed() {
            when(queue.due()).thenReturn(List.of(new FadadaSigningMemberSyncMapper.Candidate(3L, 9L, 7L)));
            when(queue.claim(eq(3L), eq(9L), anyString())).thenReturn(1);
        }
    }
}
