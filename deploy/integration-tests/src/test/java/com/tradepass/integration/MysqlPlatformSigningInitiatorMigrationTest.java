package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlPlatformSigningInitiatorMigrationTest {
    @ParameterizedTest @ValueSource(strings = {"legacy", "business", "contract"})
    void upgradeKeepsHistoricalOwnersNullAndPersistsTheFrozenPlatformOwner(String layout) throws Exception {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only a dedicated tradepass_fix_validation_* database is allowed");
        }
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String database = "tradepass_fix_validation_owner_" + layout + "_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        var source = new DriverManagerDataSource(url.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), user, password);
        var jdbc = new JdbcTemplate(source);
        String directory = switch (layout) {
            case "legacy" -> "sql/mysql";
            case "business" -> "tradepass-business/src/main/resources/db/business";
            default -> "tradepass-module-contract/tradepass-module-contract-server/src/main/resources/db/owned/contract";
        };
        String location = "filesystem:" + RepoRoot.find().resolve(directory).toAbsolutePath();
        try {
            Flyway.configure().dataSource(source).locations(location)
                    .target(layout.equals("legacy") ? "46" : layout.equals("business") ? "8" : "6").load().migrate();
            jdbc.update("""
                    INSERT INTO trade_contract(id,company_id,counterparty_company_id,counterparty_name,
                        contract_no,name,direction,status,version_no,initiated_by)
                    VALUES (1,3,4,'需方','OWNER-1','测试合同','SALE','PENDING',1,7)
                    """);
            jdbc.update("""
                    INSERT INTO fadada_contract_sign_task(id,contract_id,version_no,sign_task_id,provider_status,
                        initiator_company_id,counterparty_company_id)
                    VALUES (101,1,1,'legacy-task','SIGNING',3,4)
                    """);
            jdbc.update("""
                    INSERT INTO membership_signing_usage(contract_id,version_no,company_id,subject_key,scope_key,
                        source,status,policy_revision,operator_id,contract_snapshot,source_sha256,created_at)
                    VALUES (1,1,3,'CREDIT-3','SPECIAL','SPECIAL_QUOTA','UNCERTAIN',1,7,'{}',?,NOW())
                    """, "a".repeat(64));
            // Keep the one-migration assertion scoped to the frozen-initiator change.
            String targetVersion = layout.equals("legacy") ? "47" : layout.equals("business") ? "9" : "7";
            var flyway = Flyway.configure().dataSource(source).locations(location).target(targetVersion).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT provider_initiator_open_corp_id FROM fadada_contract_sign_task WHERE id=101", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT provider_initiator_open_corp_id FROM membership_signing_usage WHERE contract_id=1", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT sign_task_id FROM fadada_contract_sign_task WHERE id=101", String.class)).isEqualTo("legacy-task");
            assertThat(jdbc.queryForObject("SELECT status FROM membership_signing_usage WHERE contract_id=1", String.class)).isEqualTo("UNCERTAIN");
            jdbc.update("UPDATE membership_signing_usage SET provider_initiator_open_corp_id='platform-original' WHERE contract_id=1");
            assertThat(jdbc.queryForObject("SELECT provider_initiator_open_corp_id FROM membership_signing_usage WHERE contract_id=1", String.class))
                    .isEqualTo("platform-original");

            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(FadadaContractSignTaskMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(configuration);
            var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(FadadaContractSignTaskMapper.class);
            var historical = mapper.selectById(101L);
            assertThat(historical.getProviderInitiatorOpenCorpId()).isNull();
            historical.setProviderInitiatorOpenCorpId("platform-original");
            mapper.updateById(historical);
            assertThat(mapper.selectById(101L).getProviderInitiatorOpenCorpId()).isEqualTo("platform-original");
            historical.setProviderInitiatorOpenCorpId(null);
            mapper.updateById(historical);
            assertThat(mapper.selectById(101L).getProviderInitiatorOpenCorpId()).isNull();
        } finally {
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }
}
