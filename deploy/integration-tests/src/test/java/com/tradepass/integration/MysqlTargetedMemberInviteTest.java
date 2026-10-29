package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.controller.app.company.vo.InviteReqVO;
import com.tradepass.module.identity.controller.app.company.vo.JoinReqVO;
import com.tradepass.module.identity.dal.mysql.certification.CompanyCertificationApplicationMapper;
import com.tradepass.module.identity.dal.mysql.company.*;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.permission.PermDefMapper;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import com.tradepass.module.identity.service.company.CompanySearchRateLimiter;
import com.tradepass.module.identity.service.company.CompanyServiceImpl;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import com.tradepass.module.identity.service.notice.MemberRemovalNoticeService;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.module.identity.service.permission.RolePermissionServiceImpl;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlTargetedMemberInviteTest {
    @ParameterizedTest @ValueSource(strings = {"flat", "identity"})
    void oldCodesRemainUnclaimedAndOnlyTheVerifiedRecipientCanApplyOnce(String layout) throws Exception {
        String base = System.getProperty("tradepass.test.mysql.url");
        if (!base.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
        String username = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, username, password));
        String database = "tradepass_fix_validation_target_invite_" + layout + "_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(base.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), username, password);
            var jdbc = new JdbcTemplate(source);
            String location = "filesystem:" + RepoRoot.find().resolve(layout.equals("flat") ? "sql/mysql"
                    : "tradepass-module-identity/tradepass-module-identity-server/src/main/resources/db/owned/identity");
            Flyway.configure().dataSource(source).locations(location).target(layout.equals("flat") ? "50" : "5").load().migrate();
            jdbc.update("INSERT INTO sys_user(id,openid,phone,status) VALUES(7,'inviter','13800000000','ACTIVE'),(8,'recipient','13900000000','ACTIVE'),(9,'forwarded','13700000000','ACTIVE'),(10,'legacy-phone','13900000000','ACTIVE')");
            jdbc.update("INSERT INTO company(id,name,credit_code,legal_person_name,created_by) VALUES(3,'定向邀请测试企业','TARGET-INVITE','测试法人',7)");
            jdbc.update("INSERT INTO company_invite(id,company_id,code,type,used,expires_at) VALUES(11,3,'legacy-member','member',0,DATE_ADD(NOW(),INTERVAL 1 DAY))");
            var flyway = Flyway.configure().dataSource(source).locations(location).load();
            assertThat(flyway.migrate().migrationsExecuted).isOne();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(layout.equals("flat") ? "51" : "6");
            assertThat(jdbc.queryForObject("SELECT phone_verified_at FROM sys_user WHERE id=10", LocalDateTime.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT target_phone_hash FROM company_invite WHERE id=11", String.class)).isNull();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            jdbc.update("UPDATE sys_user SET phone_verified_at=NOW() WHERE id IN(7,8,9)");

            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(CompanyMapper.class);
            configuration.addMapper(CompanyMemberMapper.class);
            configuration.addMapper(CompanyInviteMapper.class);
            configuration.addMapper(SysUserMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(configuration);
            var sessions = new SqlSessionTemplate(factory.getObject());
            var users = sessions.getMapper(SysUserMapper.class);
            var service = new CompanyServiceImpl(sessions.getMapper(CompanyMapper.class), sessions.getMapper(CompanyMemberMapper.class),
                    sessions.getMapper(CompanyInviteMapper.class), mock(CounterpartyRelationMapper.class), mock(RoleDefMapper.class),
                    mock(PermDefMapper.class), mock(AccessControlService.class), mock(CompanySearchRateLimiter.class),
                    new RolePermissionServiceImpl(), mock(AuditLogService.class), mock(MemberRemovalNoticeService.class),
                    mock(FadadaCorpIdentityMapper.class), mock(CompanyCertificationApplicationMapper.class), false);
            service.setInviteUserMapper(users);
            var personal = mock(FadadaPersonalIdentityService.class);
            var verified = new PersonalIdentityRespDTO(true, "VERIFIED", "已认证", null,null,null,"受邀人实名",null,null,null,null,null);
            when(personal.current()).thenReturn(verified); when(personal.requireCurrentVerified()).thenReturn(verified);
            service.setPersonalIdentityService(personal);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            String code = as(7, () -> service.createInvite(new InviteReqVO("3", null, "13900000000")).code());
            assertThat(as(8, () -> service.previewInvite(code)).targetPhoneMasked()).isEqualTo("139****0000");
            assertThat(as(10, () -> service.previewInvite(code)).phoneBindingRequired()).isTrue();
            assertThatThrownBy(() -> as(9, () -> tx.execute(status -> service.joinCompany(new JoinReqVO(code)))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(46202));
            assertThatThrownBy(() -> as(7, () -> tx.execute(status -> service.joinCompany(new JoinReqVO(code)))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(46204));
            assertThatThrownBy(() -> as(10, () -> tx.execute(status -> service.joinCompany(new JoinReqVO(code)))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(46201));
            assertThatThrownBy(() -> as(8, () -> tx.execute(status -> service.joinCompany(new JoinReqVO("legacy-member")))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(46203));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM company_member WHERE company_id=3", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT used FROM company_invite WHERE code=?", Boolean.class, code)).isFalse();

            var executor = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);
                var one = executor.submit(() -> apply(start, tx, service, code));
                var two = executor.submit(() -> apply(start, tx, service, code));
                start.countDown();
                assertThat(one.get(15,TimeUnit.SECONDS) + two.get(15,TimeUnit.SECONDS)).isEqualTo(1);
            } finally { executor.shutdownNow(); }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM company_member WHERE company_id=3 AND user_id=8 AND status='PENDING'", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT used_by FROM company_invite WHERE code=?", Long.class, code)).isEqualTo(8);
            var user = users.selectById(8);
            user.setPhone("13500000000"); user.setPhoneVerifiedAt(null); users.updateById(user);
            assertThat(jdbc.queryForObject("SELECT phone_verified_at FROM sys_user WHERE id=8", LocalDateTime.class)).isNull();
        } finally { AuthContext.clear(); admin.execute("DROP DATABASE `" + database + "`"); }
    }

    private int apply(CountDownLatch start, TransactionTemplate tx, CompanyServiceImpl service, String code) throws Exception {
        start.await(10,TimeUnit.SECONDS);
        try { return as(8, () -> tx.execute(status -> service.joinCompany(new JoinReqVO(code)))) != null ? 1 : 0; }
        catch (BusinessException alreadyUsed) {
            assertThat(alreadyUsed.getMessage()).contains("已被使用"); return 0;
        }
    }

    private <T> T as(long user, Supplier<T> operation) {
        AuthContext.set(user, null);
        try { return operation.get(); } finally { AuthContext.clear(); }
    }
}
