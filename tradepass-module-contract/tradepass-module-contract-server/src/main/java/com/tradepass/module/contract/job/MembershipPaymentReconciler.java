package com.tradepass.module.contract.job;

import com.tradepass.module.contract.service.membership.MembershipPurchaseService;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.util.concurrent.*;

@Service
public class MembershipPaymentReconciler {
    private final MembershipPurchaseService purchases;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"membership-payment-reconcile"); t.setDaemon(true); return t;
    });
    public MembershipPaymentReconciler(MembershipPurchaseService purchases) { this.purchases=purchases; }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        executor.scheduleWithFixedDelay(()->{
            try { purchases.reconcilePending(); } catch(RuntimeException ignored) { }
        },30,30,TimeUnit.SECONDS);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
