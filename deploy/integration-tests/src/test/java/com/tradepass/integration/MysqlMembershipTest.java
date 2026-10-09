package com.tradepass.integration;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.service.membership.*;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="tradepass.test.mysql.url",matches=".+")
class MysqlMembershipTest {
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private MembershipPolicyStore policies;
    private MembershipService service;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC);
    @BeforeAll static void migrate() {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only an isolated tradepass_fix_validation_* database is allowed");
        var source = new DriverManagerDataSource(url, System.getProperty("tradepass.test.mysql.username","root"),
                System.getProperty("tradepass.test.mysql.password",""));
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).locations("filesystem:"+RepoRoot.find().resolve("sql/mysql")).load().migrate();
    }
    @BeforeEach void reset() {
        for (String table : List.of("membership_signing_usage","membership_quota_pool","membership_trial_budget",
                "membership_policy_history","membership_paid_vip")) jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE membership_policy_state SET revision=0,content_hash='',content='' WHERE id=1");
        policies = new MembershipPolicyStore(jdbc,manager);
        service = new MembershipService(jdbc,policies,manager,CLOCK);
        AuthContext.set(7,3L);
    }
    @AfterEach void clear() { AuthContext.clear(); }
    private CompanyRespDTO company(long id) {
        var c = new CompanyRespDTO(); c.setId(id); c.setName("企业"+id);
        c.setCreditCode("CREDIT-"+id); c.setCertificationStatus("VERIFIED"); return c;
    }
    private void configure(String rules) {
        policies.apply("tradepass:\n  membership:\n"+rules, "TEST");
    }
    private void quota(long total) {
        configure("    whitelist:\n      - company-id: '3'\n        mode: QUOTA\n        quota-total: "+total+"\n");
    }
    private MembershipService.Reservation reserve(long contract) {
        return service.reserve(company(3),contract,1,"{}","a".repeat(64));
    }
    private void consume(long contract) {
        var r=reserve(contract);
        service.confirm(r.id(),new FadadaSigningGateway.CreatedTask("task-"+contract,"file-"+contract,"doc"));
    }
    @Test void concurrentRequestsCannotOverdrawLastQuotaAndConfirmationIsIdempotent() throws Exception {
        quota(1);
        var executor=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(long id:List.of(100L,101L)) futures.add(executor.submit(()->{
                AuthContext.set(7,3L);
                try { start.await(); consume(id); return true; }
                catch(com.tradepass.framework.common.exception.BusinessException e) { return false; }
                finally { AuthContext.clear(); }
            }));
            start.countDown();
            assertThat(List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
            var status=service.status(company(3));
            assertThat(status.used()).isEqualTo(1); assertThat(status.reserved()).isZero();
            long id=jdbc.queryForObject("SELECT id FROM membership_signing_usage LIMIT 1",Long.class);
            service.confirm(id,new FadadaSigningGateway.CreatedTask("duplicate","f","d"));
            assertThat(service.status(company(3)).used()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void changingQuotaRemovingRulesAndUnlimitedSwitchesNeverResetUsage() {
        quota(2); consume(1);
        quota(3); assertThat(service.status(company(3)).remaining()).isEqualTo(2);
        quota(0); assertThat(service.status(company(3)).remaining()).isZero();
        configure("    whitelist: [{company-id: '3', mode: UNLIMITED}]\n");
        consume(2); assertThat(service.status(company(3)).unlimited()).isTrue();
        policies.apply("", "NACOS_MISSING");
        quota(3); assertThat(service.status(company(3)).remaining()).isEqualTo(1);
        assertThat(new MembershipService(jdbc,new MembershipPolicyStore(jdbc,manager),manager,CLOCK)
                .status(company(3)).used()).isEqualTo(2);
        var sameSubject=company(4); sameSubject.setCreditCode("CREDIT-3");
        configure("    whitelist: [{company-id: '4', mode: QUOTA, quota-total: 3}]\n");
        assertThat(service.status(sameSubject).remaining()).isEqualTo(1);
    }
    @Test void blacklistWinsButPreviouslyReservedTaskAndPaidRightsArePreserved() {
        quota(2); var r=reserve(1);
        configure("    blacklist: [{company-id: '3'}]\n    whitelist: [{company-id: '3', mode: UNLIMITED}]\n");
        assertThat(service.status(company(3)).canInitiate()).isFalse();
        assertThatThrownBy(()->reserve(2)).hasMessageContaining("暂停");
        service.confirm(r.id(),new FadadaSigningGateway.CreatedTask("old","file","doc"));
        assertThat(service.status(company(3)).used()).isEqualTo(1);
        configure("    whitelist: [{company-id: '3', mode: QUOTA, quota-total: 2}]\n");
        assertThat(service.status(company(3)).remaining()).isEqualTo(1);
    }
    @Test void trialBudgetIsAllocatedOnceAndDateExtensionDoesNotGrantAgain() {
        String trial="    trial: {enabled: true, activity-id: launch, end-at: '2026-10-31 23:59:59', sign-quota-per-company: 2, total-sign-quota: 2}\n";
        configure(trial); consume(1);
        assertThat(service.status(company(4)).canInitiate()).isFalse();
        configure(trial.replace("2026-10-31","2026-11-30").replace("per-company: 2","per-company: 5"));
        assertThat(service.status(company(3)).remaining()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT allocated_amount FROM membership_trial_budget",Long.class)).isEqualTo(2);
        configure(trial.replace("enabled: true","enabled: false")); configure(trial);
        assertThat(service.status(company(3)).remaining()).isEqualTo(1);
    }
    @Test void dedicatedQuotaDoesNotFallBackToOrdinaryTrialButCanUseIndependentPaidCredits() {
        configure("""
                    trial: {enabled: true, activity-id: launch, end-at: '2026-12-31 23:59:59'}
                    whitelist: [{company-id: '3', mode: QUOTA, quota-total: 0}]
                """);
        assertThat(service.status(company(3)).canInitiate()).isFalse();
        jdbc.update("""
                INSERT INTO membership_quota_pool(scope_key,subject_key,company_id,total_amount,expires_at)
                VALUES ('PAID:order-1','CREDIT-3',3,5,'2027-01-01 00:00:00')
                """);
        consume(1);
        assertThat(jdbc.queryForObject("SELECT source FROM membership_signing_usage",String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_trial_budget",Long.class)).isZero();
        assertThat(service.status(company(3)).paidRemaining()).isEqualTo(4);
    }
    @Test void providerReceiptSurvivesOuterRollbackAndDoesNotCreateOrChargeTwice() {
        quota(2);
        var outer=new TransactionTemplate(manager);
        assertThatThrownBy(()->outer.execute(t->{
            consume(1); throw new IllegalStateException("sign URL failed after provider success");
        })).isInstanceOf(IllegalStateException.class);
        var recovered=reserve(1);
        assertThat(recovered.createAllowed()).isFalse(); assertThat(recovered.signTaskId()).isEqualTo("task-1");
        assertThat(service.status(company(3)).used()).isEqualTo(1);
    }
    @Test void uncertainCreationHoldsItsReservationAndCannotBeBlindlyRetried() {
        quota(2); var r=reserve(1); service.uncertain(r.id());
        assertThatThrownBy(()->reserve(1)).hasMessageContaining("待核实");
        assertThat(service.status(company(3)).reserved()).isEqualTo(1);
        service.confirm(r.id(),new FadadaSigningGateway.CreatedTask("recovered","f","d"));
        assertThat(service.status(company(3)).used()).isEqualTo(1);
        assertThat(service.status(company(3)).reserved()).isZero();
    }
    @Test void independentWhitelistExpiryAndInvalidConfigAreHandledWithoutErasingPolicy() {
        configure("    trial: {enabled: false}\n    whitelist: [{company-id: '3', mode: UNLIMITED, valid-until: '2026-11-30 23:59:59'}]\n");
        assertThat(service.status(company(3)).unlimited()).isTrue();
        assertThatThrownBy(()->configure("    whitelist: [{company-id: '3', mode: BROKEN}]\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.status(company(3)).unlimited()).isTrue();
        var later=new MembershipService(jdbc,policies,manager,
                Clock.fixed(Instant.parse("2026-11-30T16:00:00Z"),ZoneOffset.UTC));
        assertThat(later.status(company(3)).vip()).isFalse();
        assertThat(later.status(company(3)).canInitiate()).isFalse();
    }

    @Test
    @EnabledIfSystemProperty(named="tradepass.test.nacos.server",matches=".+")
    void actualNacosPublishInvalidUpdateAndDeleteRefreshWithoutRestartOrResettingUsage() throws Exception {
        var props=new com.alibaba.cloud.nacos.NacosConfigProperties();
        props.setServerAddr(System.getProperty("tradepass.test.nacos.server"));
        var nacos=new com.alibaba.cloud.nacos.NacosConfigManager(props);
        var config=nacos.getConfigService();
        String dataId="membership-test-"+System.nanoTime()+".yaml", group="MEMBERSHIP_TEST";
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("nacos",nacos);
        var environment=new org.springframework.mock.env.MockEnvironment()
                .withProperty("spring.cloud.nacos.config.enabled","true")
                .withProperty("spring.cloud.nacos.config.group",group)
                .withProperty("tradepass.membership.data-id",dataId);
        var loader=new com.tradepass.module.contract.framework.membership.MembershipNacosLoader(
                policies,factory.getBeanProvider(com.alibaba.cloud.nacos.NacosConfigManager.class),environment);
        String prefix="tradepass:\n  membership:\n    whitelist: [{company-id: '3', mode: QUOTA, quota-total: ";
        try {
            assertThat(config.publishConfig(dataId,group,prefix+"1}]\n")).isTrue();
            loader.start();
            await(()->service.status(company(3)).remaining()!=null && service.status(company(3)).remaining()==1);
            consume(10);
            assertThat(config.publishConfig(dataId,group,prefix+"3}]\n")).isTrue();
            await(()->service.status(company(3)).remaining()==2);
            assertThat(service.status(company(3)).used()).isEqualTo(1);
            assertThat(config.publishConfig(dataId,group,prefix+"-1}]\n")).isTrue();
            await(()->!policies.error().isBlank());
            assertThat(service.status(company(3)).remaining()).isEqualTo(2);
            assertThat(config.removeConfig(dataId,group)).isTrue();
            await(()->!service.status(company(3)).vip());
            assertThat(service.status(company(3)).canInitiate()).isFalse();
        } finally { loader.close(); config.removeConfig(dataId,group); config.shutDown(); }
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until) {
            if(condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Nacos policy did not refresh");
    }
}
