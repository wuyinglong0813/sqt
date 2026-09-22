package com.tradepass.framework.rpc.config;

import org.apache.seata.core.context.RootContext;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Carries the existing Spring transaction and rollback rules across service boundaries. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "tradepass.services.split", havingValue = "true")
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class DistributedTransactionConfiguration {
    @Bean WorkflowTransactions workflowTransactions(PlatformTransactionManager manager) {
        return new WorkflowTransactions(manager);
    }

    @Aspect
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    public static class WorkflowTransactions {
        private final AnnotationTransactionAttributeSource attributes = new AnnotationTransactionAttributeSource();
        private final PlatformTransactionManager manager;
        public WorkflowTransactions(PlatformTransactionManager manager) { this.manager = manager; }

        @Around("execution(public * com.tradepass..service..*(..)) && @annotation(org.springframework.transaction.annotation.Transactional)")
        public Object invoke(ProceedingJoinPoint call) throws Throwable {
            var method = ((MethodSignature) call.getSignature()).getMethod();
            var attribute = attributes.getTransactionAttribute(method, call.getTarget().getClass());
            if (attribute == null) return call.proceed();
            int propagation = attribute.getPropagationBehavior();
            if (propagation == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                    || propagation == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
                // Provider creation intents deliberately survive failure of the surrounding workflow.
                String xid = RootContext.unbind();
                try { return call.proceed(); }
                finally { if (xid != null) RootContext.bind(xid); }
            }
            if (RootContext.inGlobalTransaction()) {
                if (propagation == TransactionDefinition.PROPAGATION_MANDATORY
                        && !TransactionSynchronizationManager.isActualTransactionActive()) {
                    // The original caller's transaction is remote: start its local Seata branch.
                    var local = new TransactionTemplate(manager);
                    local.setIsolationLevel(attribute.getIsolationLevel());
                    local.setReadOnly(attribute.isReadOnly());
                    Throwable[] failure = {null};
                    Object result = local.execute(status -> {
                        try { return call.proceed(); }
                        catch (Throwable error) {
                            failure[0] = error;
                            if (attribute.rollbackOn(error)) status.setRollbackOnly();
                            return null;
                        }
                    });
                    if (failure[0] != null) throw failure[0];
                    return result;
                }
                return call.proceed();
            }
            if (propagation == TransactionDefinition.PROPAGATION_MANDATORY
                    || propagation == TransactionDefinition.PROPAGATION_NEVER
                    || propagation == TransactionDefinition.PROPAGATION_SUPPORTS
                    || TransactionSynchronizationManager.isActualTransactionActive()) return call.proceed();

            var global = GlobalTransactionContext.getCurrentOrCreate();
            global.begin(180_000, call.getTarget().getClass().getSimpleName() + "." + method.getName());
            Object result;
            try { result = call.proceed(); }
            catch (Throwable failure) {
                try {
                    if (attribute.rollbackOn(failure)) global.rollback();
                    else global.commit();
                } catch (Throwable completionFailure) { failure.addSuppressed(completionFailure); }
                throw failure;
            }
            global.commit();
            return result;
        }
    }
}
