package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.api.company.CompanyReaderImpl;
import com.tradepass.module.identity.dal.mysql.company.CompanyReferralMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaSigningMemberSyncMapper;
import com.tradepass.module.identity.service.company.CompanyReferralService;
import com.tradepass.module.identity.service.fadada.FadadaSigningAuthorizationService;
import com.tradepass.module.identity.service.fadada.FadadaSigningMemberSyncService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Use the exact production scan configurations, including their BaseMapper marker filter. */
class SigningMemberMapperProductionScannerTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void productionMapperScanCanConstructIdentityServices(boolean split) throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty("tradepass.runtime.role", "identity")
                    .withProperty("tradepass.services.split", String.valueOf(split)));
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(mock(DataSource.class)); factory.setConfiguration(configuration);
            var sessions = factory.getObject();
            context.registerBean("sqlSessionFactory", SqlSessionFactory.class, () -> sessions);
            context.registerBean(FadadaSigningAuthorizationService.class, () -> mock(FadadaSigningAuthorizationService.class));
            context.registerBean(FadadaProperties.class, FadadaProperties::new);
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(FadadaSigningMemberSyncService.class);
            context.registerBean(CompanyReaderImpl.class);
            context.registerBean(CompanyReferralService.class);
            context.register(Class.forName("com.tradepass.framework.runtime.config.BusinessRuntimeConfiguration$"
                    + (split ? "OwnedDatabaseMappers" : "SharedDatabaseMappers")));
            context.refresh();
            assertThat(context.getBean(FadadaSigningMemberSyncMapper.class)).isNotNull();
            assertThat(context.getBean(FadadaSigningMemberSyncService.class)).isNotNull();
            assertThat(sessions.getConfiguration().hasStatement(FadadaSigningMemberSyncMapper.class.getName() + ".claim"))
                    .isTrue();
            assertThat(context.getBean(CompanyReferralMapper.class)).isNotNull();
            assertThat(context.getBean(CompanyReaderImpl.class)).isNotNull();
            assertThat(context.getBean(CompanyReferralService.class)).isNotNull();
            assertThat(sessions.getConfiguration().hasStatement(CompanyReferralMapper.class.getName() + ".insertConfirmed"))
                    .isTrue();
            assertThat(sessions.getConfiguration().hasStatement(CompanyReferralMapper.class.getName() + ".countConfirmed"))
                    .isTrue();
        }
    }
}
