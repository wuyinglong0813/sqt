package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
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
class MysqlVoidedContractVisibilityTest {
    @ParameterizedTest @ValueSource(strings = {"legacy", "business", "contract"})
    void migrationPreservesRecordsAndEachPartyHidesOnlyItsOwnList(String layout) throws Exception {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only a dedicated tradepass_fix_validation_* database is allowed");
        }
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String database = "tradepass_fix_validation_visibility_" + layout + "_" + Long.toUnsignedString(System.nanoTime());
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
                    .target(layout.equals("legacy") ? "45" : layout.equals("business") ? "7" : "5").load().migrate();
            jdbc.update("""
                    INSERT INTO trade_contract(id,company_id,counterparty_company_id,counterparty_name,
                        contract_no,name,direction,status,version_no,initiated_by)
                    VALUES (1,3,4,'需方','VISIBILITY-1','测试合同','SALE','VOIDED',1,7)
                    """);
            var flyway = Flyway.configure().dataSource(source).locations(location)
                    .target(layout.equals("legacy") ? "46" : layout.equals("business") ? "8" : "6").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT counterparty_hidden FROM trade_contract WHERE id=1", Boolean.class)).isFalse();
            // Standalone contract storage has no identity table. The legacy list mapper joins only the name.
            jdbc.execute("CREATE TABLE IF NOT EXISTS company (id BIGINT PRIMARY KEY, name VARCHAR(255))");
            if (layout.equals("legacy")) {
                jdbc.update("INSERT INTO company(id,name,credit_code,legal_person_name,created_by) VALUES(3,'供方','VISIBILITY-3','法人',7)");
            } else jdbc.update("INSERT INTO company(id,name) VALUES(3,'供方')");
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(TradeContractMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(configuration);
            var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(TradeContractMapper.class);
            assertThat(mapper.countPartyContracts(3L, null, "VOIDED", null)).isEqualTo(1);
            assertThat(mapper.countPartyContracts(4L, null, "VOIDED", null)).isEqualTo(1);
            jdbc.update("UPDATE trade_contract SET counterparty_hidden=1 WHERE id=1");
            assertThat(mapper.selectPartyContracts(4L, null, "VOIDED", null, 20, 0)).isEmpty();
            assertThat(mapper.countPartyContracts(4L, null, "VOIDED", null)).isZero();
            assertThat(((Number) mapper.selectContractSummary(4L).get("total")).intValue()).isZero();
            assertThat(mapper.selectPartyContracts(3L, null, "VOIDED", null, 20, 0)).hasSize(1);
            jdbc.update("UPDATE trade_contract SET initiator_hidden=1 WHERE id=1");
            assertThat(mapper.countPartyContracts(3L, null, "VOIDED", null)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM trade_contract WHERE id=1", String.class)).isEqualTo("VOIDED");
        } finally {
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }
}
