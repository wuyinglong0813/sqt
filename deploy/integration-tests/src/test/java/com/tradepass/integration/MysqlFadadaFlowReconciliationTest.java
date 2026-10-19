package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

/** Upgrade real pre-fix schemas and exercise the exact mapper SQL used by background recovery. */
@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlFadadaFlowReconciliationTest {
    @ParameterizedTest @ValueSource(strings = {"business", "identity", "contract"})
    void upgradesPreserveOldBindingsAndPollingUsesDurableClaims(String role) throws Exception {
        String base = System.getProperty("tradepass.test.mysql.url");
        if (!base.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, user, password));
        String database = "tradepass_fix_validation_fadada_" + role + "_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        var source = new DriverManagerDataSource(base.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), user, password);
        var jdbc = new JdbcTemplate(source);
        String path = role.equals("business") ? "tradepass-business/src/main/resources/db/business"
                : "tradepass-module-" + role + "/tradepass-module-" + role + "-server/src/main/resources/db/owned/" + role;
        String location = "filesystem:" + RepoRoot.find().resolve(path).toAbsolutePath();
        Flyway.configure().dataSource(source).locations(location).target(switch(role) {
            case "business" -> "5"; case "identity" -> "2"; default -> "4";
        }).load().migrate();
        if (role.equals("identity")) jdbc.update("""
                INSERT INTO fadada_corp_identity(id,company_id,applicant_user_id,client_corp_id,open_corp_id,
                    local_status,auth_scopes,last_sync_at) VALUES (1,3,99,'old-corp','corp-3','VERIFIED','[]','2020-01-01')
                """);
        var flyway = Flyway.configure().dataSource(source).locations(location).load();
        flyway.migrate(); flyway.validate();
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        if (role.equals("identity")) {
            configuration.addMapper(FadadaUserIdentityMapper.class); configuration.addMapper(FadadaCorpIdentityMapper.class);
        }
        if (!role.equals("identity")) configuration.addMapper(FadadaContractSignTaskMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (role.equals("identity")) {
            var corp = session.getMapper(FadadaCorpIdentityMapper.class);
            assertThat(corp.selectById(1L).getApplicantUserId()).isEqualTo(99L);
            assertThat(corp.findForReconciliation(now.minusMinutes(15), now.minusMinutes(2), now.minusMinutes(1)))
                    .extracting(x -> x.getId()).contains(1L);
            assertThat(corp.claimReconciliation(1L, now, now.minusMinutes(15))).isOne();
            assertThat(corp.claimReconciliation(1L, now, now.minusMinutes(15))).isZero();
            assertThat(corp.findForReconciliation(now.minusMinutes(15), now.minusMinutes(2), now.minusMinutes(1))).isEmpty();
            jdbc.update("INSERT INTO fadada_user_identity(id,user_id,client_user_id,open_user_id,local_status,last_sync_at) VALUES (2,7,'old-user','user-7','VERIFIED','2020-01-01')");
            var people = session.getMapper(FadadaUserIdentityMapper.class);
            assertThat(people.findForReconciliation(now.minusMinutes(15), now.minusMinutes(2))).hasSize(1);
            assertThat(people.claimReconciliation(2L, now, now.minusMinutes(15))).isOne();
            assertThat(people.claimReconciliation(2L, now, now.minusMinutes(15))).isZero();
        }
        if (!role.equals("identity")) {
            for (int id : new int[]{101,102,103,104,105}) {
                jdbc.update("INSERT INTO trade_contract(id,contract_no,company_id,counterparty_name,name,initiated_by,status,version_no) VALUES (?,?,3,'测试相对方','测试合同',7,?,2)",
                        id, "HT-FLOW-" + id, id == 102 || id == 105 ? "ACTIVE" : "PENDING");
                jdbc.update("""
                        INSERT INTO fadada_contract_sign_task(id,contract_id,version_no,sign_task_id,provider_status,
                            initiator_company_id,counterparty_company_id,updated_at)
                        VALUES (?,?,?,? ,?,3,4,?)
                        """, id, id, id == 104 ? 1 : 2, "task-" + id,
                        id == 103 ? "expired" : "sign_progress", id == 105 ? now.minusDays(2) : now.minusMinutes(20));
            }
            var tasks = session.getMapper(FadadaContractSignTaskMapper.class);
            var due = tasks.findDueReconciliation(now.minusMinutes(5), now.minusHours(12), 20);
            assertThat(due).extracting(x -> x.getId()).containsExactlyInAnyOrder(101L,105L);
            var first = due.stream().filter(x -> x.getId() == 101L).findFirst().orElseThrow();
            assertThat(tasks.claimReconciliation(101L, first.getUpdatedAt(), now)).isOne();
            assertThat(tasks.claimReconciliation(101L, first.getUpdatedAt(), now)).isZero();
            assertThat(tasks.findDueReconciliation(now.minusMinutes(5), now.minusHours(12), 20))
                    .extracting(x -> x.getId()).doesNotContain(101L);
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
    }
}
