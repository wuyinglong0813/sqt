package com.tradepass.framework.rpc.config;

import com.tradepass.framework.common.exception.BusinessException;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.tm.api.GlobalTransaction;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DistributedTransactionRulesTest {
    final DistributedTransactionConfiguration.WorkflowTransactions advice =
            new DistributedTransactionConfiguration.WorkflowTransactions(new LocalManager());

    @AfterEach void clearContext() { RootContext.unbind(); }

    @Test void disabledSeataKeepsTheExistingSpringTransaction() throws Throwable {
        var local = new DistributedTransactionConfiguration.WorkflowTransactions(new LocalManager(), false);
        var call = call("normal");
        when(call.proceed()).thenReturn("local");
        assertEquals("local", local.invoke(call));
        verify(call).proceed();
    }

    @Test void failureRollsBackButOriginalNoRollbackForStillCommits() throws Throwable {
        var global = mock(GlobalTransaction.class);
        try (var globals = mockStatic(GlobalTransactionContext.class)) {
            globals.when(GlobalTransactionContext::getCurrentOrCreate).thenReturn(global);
            var call = call("normal");
            when(call.proceed()).thenThrow(new BusinessException("original failure"));
            assertThrows(BusinessException.class, () -> advice.invoke(call));
            verify(global).rollback();
            verify(global, never()).commit();
            reset(global);
            var keep = call("keepIntent");
            when(keep.proceed()).thenThrow(new BusinessException("original failure"));
            assertThrows(BusinessException.class, () -> advice.invoke(keep));
            verify(global).commit();
            verify(global, never()).rollback();
        }
    }

    @Test void independentCreationIntentTemporarilyLeavesOuterGlobalTransaction() throws Throwable {
        RootContext.bind("test-coordinator:8091:17");
        var call = call("independent");
        when(call.proceed()).thenAnswer(invocation -> {
            assertNull(RootContext.getXID());
            throw new BusinessException("intent failure");
        });
        assertThrows(BusinessException.class, () -> advice.invoke(call));
        assertEquals("test-coordinator:8091:17", RootContext.getXID());
    }

    @Test void mandatoryRemoteParticipantGetsALocalTransactionWithoutStartingAnotherGlobal() throws Throwable {
        RootContext.bind("test-coordinator:8091:18");
        var call = call("mandatory");
        when(call.proceed()).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("test-coordinator:8091:18", RootContext.getXID());
            return "same result";
        });
        assertEquals("same result", advice.invoke(call));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private static ProceedingJoinPoint call(String method) throws Exception {
        var call = mock(ProceedingJoinPoint.class);
        var signature = mock(MethodSignature.class);
        when(call.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(Rules.class.getMethod(method));
        when(call.getTarget()).thenReturn(new Rules());
        return call;
    }

    public static class Rules {
        @Transactional public void normal() { }
        @Transactional(noRollbackFor = BusinessException.class) public void keepIntent() { }
        @Transactional(propagation = Propagation.REQUIRES_NEW) public void independent() { }
        @Transactional(propagation = Propagation.MANDATORY) public void mandatory() { }
    }

    static class LocalManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
