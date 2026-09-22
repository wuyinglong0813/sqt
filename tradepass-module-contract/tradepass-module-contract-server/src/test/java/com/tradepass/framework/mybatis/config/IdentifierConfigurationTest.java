package com.tradepass.framework.mybatis.config;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.mybatis.core.ApplicationIds;
import com.tradepass.module.contract.controller.app.contract.vo.CreateContractReqVO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.support.TestIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifierConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(IdentifierConfiguration.class);

    @AfterEach void restoreGenerator() { TestIds.reset(); }

    @Test void entityMapAndPrimitiveIdsSurviveJsonRoundTripWithoutLosingDigits() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            long id = 2098123456789012345L;
            TradeContractDO contract = new TradeContractDO();
            contract.setId(id); contract.setCompanyId(id + 1);
            String json = mapper.writeValueAsString(ApiResponse.ok(Map.of(
                    "contract", contract, "nested", List.of(Map.of("id", id)),
                    "primitive", new PrimitiveId(id), "count", 3)));
            var root = mapper.readTree(json);
            assertThat(root.at("/data/contract/id").isTextual()).isTrue();
            assertThat(root.at("/data/contract/id").asText()).isEqualTo(Long.toString(id));
            assertThat(root.at("/data/contract/companyId").asText()).isEqualTo(Long.toString(id + 1));
            assertThat(root.at("/data/nested/0/id").asText()).isEqualTo(Long.toString(id));
            assertThat(root.at("/data/primitive/id").isTextual()).isTrue();
            assertThat(root.at("/data/count").isInt()).isTrue();
            assertThat(root.at("/code").isInt()).isTrue();
            var request = mapper.readValue("{\"counterpartyCompanyId\":\"" + id + "\"}", CreateContractReqVO.class);
            assertThat(request.counterpartyCompanyId()).isEqualTo(id);
            assertThat(mapper.treeToValue(root.at("/data/contract"), TradeContractDO.class).getId()).isEqualTo(id);
        });
    }

    @Test void jdbcAndMapperUseTheSameConfiguredNodeAndGenerateUniqueIdsConcurrently() {
        runner.withPropertyValues("tradepass.ids.worker-id=3", "tradepass.ids.datacenter-id=7").run(context -> {
            IdentifierGenerator generator = context.getBean(IdentifierGenerator.class);
            Set<Long> ids = ConcurrentHashMap.newKeySet();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> jobs = new ArrayList<>();
                for (int thread = 0; thread < 8; thread++) {
                    jobs.add(pool.submit(() -> {
                        for (int index = 0; index < 1000; index++) {
                            long id = index % 2 == 0 ? ApplicationIds.next() : generator.nextId(null).longValue();
                            assertThat((id >> 12) & 31).isEqualTo(3);
                            assertThat((id >> 17) & 31).isEqualTo(7);
                            assertThat(ids.add(id)).isTrue();
                        }
                    }));
                }
                for (Future<?> job : jobs) job.get(10, TimeUnit.SECONDS);
                assertThat(ids).hasSize(8000);
            } finally { pool.shutdownNow(); }
        });
    }

    @Test void nodeCanBeSetThroughDeploymentEnvironmentVariables() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("id-test-env", Map.of(
                        "TRADEPASS_IDS_WORKER_ID", "4", "TRADEPASS_IDS_DATACENTER_ID", "2"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    long id = ApplicationIds.next();
                    assertThat((id >> 12) & 31).isEqualTo(4);
                    assertThat((id >> 17) & 31).isEqualTo(2);
                });
    }

    @Test void refusesIncompleteOrOutOfRangeNodeConfiguration() {
        for (String[] properties : List.of(
                new String[]{"tradepass.ids.worker-id=1"},
                new String[]{"tradepass.ids.datacenter-id=1"},
                new String[]{"tradepass.ids.worker-id=32", "tradepass.ids.datacenter-id=0"},
                new String[]{"tradepass.ids.worker-id=0", "tradepass.ids.datacenter-id=-1"})) {
            runner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed());
        }
    }

    record PrimitiveId(long id) { }
}
