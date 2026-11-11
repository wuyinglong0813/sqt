package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.controller.app.company.vo.*;
import com.tradepass.module.identity.dal.mysql.certification.CompanyCertificationApplicationMapper;
import com.tradepass.module.identity.dal.mysql.company.*;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.permission.PermDefMapper;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import com.tradepass.module.identity.service.company.*;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import com.tradepass.module.identity.service.notice.MemberRemovalNoticeService;
import com.tradepass.module.identity.service.permission.*;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlOpenMemberInviteTest {
    @ParameterizedTest @ValueSource(strings = {"flat", "identity"})
    void sharedInvitationsKeepApplicantsIsolatedUntilIdentityAndApproval(String layout) throws Exception {
        String base = System.getProperty("tradepass.test.mysql.url");
        if (!base.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
        String username = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, username, password));
        String database = "tradepass_fix_validation_open_invite_" + layout + "_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(base.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), username, password);
            var jdbc = new JdbcTemplate(source);
            String location = "filesystem:" + RepoRoot.find().resolve(layout.equals("flat") ? "sql/mysql"
                    : "tradepass-module-identity/tradepass-module-identity-server/src/main/resources/db/owned/identity");
            Flyway.configure().dataSource(source).locations(location).target(layout.equals("flat") ? "51" : "6").load().migrate();
            jdbc.update("INSERT INTO sys_user(id,openid,phone,status,phone_verified_at) VALUES(7,'inviter','13800000000','ACTIVE',NOW()),(8,'applicant-a','13900000000','ACTIVE',NOW()),(9,'applicant-b','13700000000','ACTIVE',NOW())");
            jdbc.update("INSERT INTO company(id,name,credit_code,legal_person_name,created_by) VALUES(3,'开放邀请测试企业','OPEN-INVITE','测试法人',7)");
            jdbc.update("INSERT INTO company_invite(id,company_id,code,type,used,expires_at) VALUES(11,3,'LEGACY23','member',0,DATE_ADD(NOW(),INTERVAL 1 DAY))");
            var flyway = Flyway.configure().dataSource(source).locations(location)
                    .target(layout.equals("flat") ? "52" : "7").load();
            assertThat(flyway.migrate().migrationsExecuted).isOne();
            flyway.validate();
            assertThat(jdbc.queryForObject("SELECT invitation_mode FROM company_invite WHERE id=11", String.class)).isEqualTo("LEGACY");
            assertThat(flyway.migrate().migrationsExecuted).isZero();

            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(CompanyMapper.class); config.addMapper(CompanyMemberMapper.class);
            config.addMapper(CompanyInviteMapper.class); config.addMapper(SysUserMapper.class);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
            var sessions = new SqlSessionTemplate(factory.getObject());
            var roleMapper = mock(RoleDefMapper.class);
            var role = new RoleDefDO(); role.setId(12L); role.setCompanyId(3L); role.setCode("SALES"); role.setName("销售员"); role.setPermissions("[]");
            when(roleMapper.selectOne(any())).thenReturn(role);
            var service = new CompanyServiceImpl(sessions.getMapper(CompanyMapper.class), sessions.getMapper(CompanyMemberMapper.class),
                    sessions.getMapper(CompanyInviteMapper.class), mock(CounterpartyRelationMapper.class), roleMapper,
                    mock(PermDefMapper.class), mock(AccessControlService.class), mock(CompanySearchRateLimiter.class),
                    new RolePermissionServiceImpl(), mock(AuditLogService.class), mock(MemberRemovalNoticeService.class),
                    mock(FadadaCorpIdentityMapper.class), mock(CompanyCertificationApplicationMapper.class), false);
            service.setInviteUserMapper(sessions.getMapper(SysUserMapper.class));
            var personal = mock(FadadaPersonalIdentityService.class);
            AtomicBoolean verified = new AtomicBoolean(false);
            var confirmed = new PersonalIdentityRespDTO(true,"VERIFIED","已认证",null,null,null,"已核验姓名",null,null,null,null,null);
            var unconfirmed = new PersonalIdentityRespDTO(true,"NOT_STARTED","未认证",null,null,null,null,null,null,null,null,null);
            when(personal.current()).thenAnswer(call -> verified.get() ? confirmed : unconfirmed);
            when(personal.requireCurrentVerified()).thenAnswer(call -> {
                if (!verified.get()) throw new BusinessException(46103,"请先完成个人实名认证");
                return confirmed;
            });
            when(personal.verifiedOpenUserId(anyLong())).thenAnswer(call -> verified.get() ? "verified-person" : null);
            service.setPersonalIdentityService(personal);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            String firstCode = as(7, () -> service.createInvite(new InviteReqVO("3", null)).code());
            String secondCode = as(7, () -> service.createInvite(new InviteReqVO("3", null)).code());
            assertThat(as(8, () -> service.previewInvite(firstCode)).invitationMode()).isEqualTo("OPEN");
            assertThatThrownBy(() -> as(8, () -> tx.execute(s -> service.joinCompany(new JoinReqVO("LEGACY23")))))
                    .isInstanceOf(BusinessException.class);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);
                var one = pool.submit(() -> apply(start, tx, service, firstCode));
                var two = pool.submit(() -> apply(start, tx, service, secondCode));
                start.countDown();
                assertThat(one.get(20, TimeUnit.SECONDS)).isEqualTo(two.get(20, TimeUnit.SECONDS));
            } finally { pool.shutdownNow(); }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM company_member WHERE company_id=3 AND user_id=8", Long.class)).isOne();
            String applicationId = jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM company_member WHERE company_id=3 AND user_id=8", String.class);
            assertThat(jdbc.queryForObject("SELECT status FROM company_member WHERE id=?", String.class, applicationId)).isEqualTo("PENDING_IDENTITY");
            assertThat(as(8, () -> service.myMemberApplications()).get(0).companyName()).isEqualTo("开放邀请测试企业");
            assertThat(sessions.getMapper(CompanyMemberMapper.class).selectUserCompanies(8L)).isEmpty();
            assertThatThrownBy(() -> as(8, () -> tx.execute(s -> service.markMemberApplicationIdentityReady(applicationId))))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> as(9, () -> tx.execute(s -> service.markMemberApplicationIdentityReady(applicationId))))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> as(7, () -> tx.execute(s -> service.approveMember(applicationId,new ApproveReqVO("SALES",List.of()),"3"))))
                    .isInstanceOf(BusinessException.class);

            var other = as(9, () -> tx.execute(s -> service.joinCompany(new JoinReqVO(firstCode))));
            assertThat(other.status()).isEqualTo("PENDING_IDENTITY");
            assertThat(jdbc.queryForObject("SELECT used FROM company_invite WHERE code=?", Boolean.class, firstCode)).isFalse();
            verified.set(true);
            assertThat(as(8, () -> tx.execute(s -> service.markMemberApplicationIdentityReady(applicationId))).status()).isEqualTo("PENDING");
            assertThat(sessions.getMapper(CompanyMemberMapper.class).selectUserCompanies(8L)).isEmpty();
            var accepted = as(7, () -> tx.execute(s -> service.approveMember(applicationId,new ApproveReqVO("SALES",List.of()),"3")));
            assertThat(accepted.status()).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT status FROM company_member WHERE user_id=9 AND company_id=3", String.class)).isEqualTo("PENDING_IDENTITY");
            assertThat(sessions.getMapper(CompanyMemberMapper.class).selectUserCompanies(8L)).hasSize(1);
        } finally { AuthContext.clear(); admin.execute("DROP DATABASE `" + database + "`"); }
    }

    private String apply(CountDownLatch start, TransactionTemplate tx, CompanyServiceImpl service, String code) throws Exception {
        start.await(10,TimeUnit.SECONDS);
        return as(8, () -> tx.execute(s -> service.joinCompany(new JoinReqVO(code)))).applicationId();
    }
    private <T> T as(long user, Supplier<T> action) {
        AuthContext.set(user,null);
        try { return action.get(); } finally { AuthContext.clear(); }
    }
}
