package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaSigningMemberSyncMapper;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/** Real MySQL SQL, migration, lease and crash recovery checks against an isolated throwaway schema. */
@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlSigningMemberPreparationTest {
    @Test void upgradedLegacyActiveMembersAreQueuedOnceAndLeasedWithoutLosingPendingAuthorization() throws Exception {
        String base = System.getProperty("tradepass.test.mysql.url");
        if (!base.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, user, password));
        String database = "tradepass_fix_validation_member_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        var source = new DriverManagerDataSource(base.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), user, password);
        var jdbc = new JdbcTemplate(source);
        String location = "filesystem:" + RepoRoot.find().resolve(
                "tradepass-module-identity/tradepass-module-identity-server/src/main/resources/db/owned/identity").toAbsolutePath();
        Flyway.configure().dataSource(source).locations(location).target("3").load().migrate();
        jdbc.update("INSERT INTO company_member(id,company_id,user_id,role_code,status) VALUES (101,3,7,'SALES','ACTIVE'),(102,3,8,'SALES','PENDING')");
        jdbc.update("INSERT INTO fadada_user_identity(id,user_id,client_user_id,local_status) VALUES (1,7,'user-7','VERIFIED'),(2,8,'user-8','VERIFIED')");
        jdbc.update("INSERT INTO fadada_corp_identity(id,company_id,applicant_user_id,client_corp_id,auth_scopes,local_status) VALUES (1,3,7,'corp-3','[]','VERIFIED')");
        var flyway = Flyway.configure().dataSource(source).locations(location).load();
        flyway.migrate(); flyway.validate();

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true); configuration.addMapper(FadadaSigningMemberSyncMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        var queue = session.getMapper(FadadaSigningMemberSyncMapper.class);
        assertThat(queue.backfill()).isOne();
        assertThat(queue.backfill()).isZero();
        assertThat(queue.due()).containsExactly(new FadadaSigningMemberSyncMapper.Candidate(3L, 7L, null));
        var contenders = Executors.newFixedThreadPool(2);
        String winningLease;
        try {
            var start = new CountDownLatch(1);
            var first = contenders.submit(() -> { start.await(); return queue.claim(3L, 7L, "lease-1"); });
            var second = contenders.submit(() -> { start.await(); return queue.claim(3L, 7L, "lease-2"); });
            start.countDown();
            int firstClaim = first.get();
            assertThat(firstClaim + second.get()).isOne();
            winningLease = firstClaim == 1 ? "lease-1" : "lease-2";
        } finally { contenders.shutdownNow(); }
        assertThat(queue.due()).isEmpty();
        assertThat(queue.finish(3L, 7L, "wrong-lease", "READY", 0, "", 86400)).isZero();
        assertThat(queue.finish(3L, 7L, winningLease, "WAITING_COMPANY_AUTHORIZATION", 46101, "组织数据授权待补齐", 600)).isOne();
        assertThat(jdbc.queryForMap("SELECT status,last_error_code FROM fadada_signing_member_sync WHERE company_id=3 AND user_id=7"))
                .containsEntry("status", "WAITING_COMPANY_AUTHORIZATION").containsEntry("last_error_code", 46101);

        assertThat(queue.enqueue(3L, 7L, 99L, "MEMBER_ROLE_CHANGED")).isPositive();
        assertThat(queue.due()).containsExactly(new FadadaSigningMemberSyncMapper.Candidate(3L, 7L, 99L));
        assertThat(queue.claim(3L, 7L, "lease-3")).isOne();
        // An event arriving during the existing lease cannot steal the worker's claim.
        queue.enqueue(3L, 7L, 99L, "PERSONAL_IDENTITY_VERIFIED");
        assertThat(queue.claim(3L, 7L, "lease-4")).isZero();
        assertThat(queue.finish(3L, 7L, "lease-3", "READY", 0, "", 86400)).isOne();
        // The old worker's successful result must not consume the newer role/identity event.
        assertThat(queue.due()).containsExactly(new FadadaSigningMemberSyncMapper.Candidate(3L, 7L, 99L));
        assertThat(jdbc.queryForObject("SELECT status FROM fadada_signing_member_sync WHERE company_id=3 AND user_id=7", String.class))
                .isEqualTo("PENDING");
        assertThat(queue.claim(3L, 7L, "lease-3b")).isOne();
        jdbc.update("UPDATE fadada_signing_member_sync SET lease_until = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)");
        assertThat(queue.claim(3L, 7L, "lease-4")).isOne();
        assertThat(queue.finish(3L, 7L, "lease-3b", "READY", 0, "", 86400)).isZero();
        assertThat(queue.finish(3L, 7L, "lease-4", "ACTIVATION_REQUIRED", 46102, "本人待确认加入", 600)).isOne();
        assertThat(queue.enqueue(3L, 8L, 99L, "MEMBER_APPROVED")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fadada_signing_member_sync", Long.class)).isEqualTo(1L);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
