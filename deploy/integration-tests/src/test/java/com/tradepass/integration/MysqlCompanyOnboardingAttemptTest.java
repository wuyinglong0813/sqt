package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.controller.app.company.vo.CompanySubmitReqVO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyOnboardingAttemptDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyOnboardingAttemptMapper;
import com.tradepass.module.identity.dal.mysql.certification.CompanyCertificationApplicationMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.service.company.CompanyOnboardingAttemptService;
import com.tradepass.module.identity.service.company.TenantBootstrapService;
import com.tradepass.module.identity.service.certification.CompanyCertificationServiceImpl;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real production mappers and MySQL row locks; provider traffic is replaced by a verified-person fixture. */
@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlCompanyOnboardingAttemptTest {
    private static final AtomicLong IDS = new AtomicLong(800_000L);
    private static Schema schema;
    private static TransactionTemplate tx;
    private static CompanyOnboardingAttemptService service;
    private static FadadaCorpIdentityMapper identities;
    private static CompanyCertificationServiceImpl certifications;

    @BeforeAll static void database() throws Exception {
        schema = new Schema("identity");
        schema.flyway(null).migrate();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CompanyMapper.class);
        configuration.addMapper(CompanyOnboardingAttemptMapper.class);
        configuration.addMapper(CompanyMemberMapper.class);
        configuration.addMapper(FadadaCorpIdentityMapper.class);
        configuration.addMapper(CompanyCertificationApplicationMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(schema.source);
        factory.setConfiguration(configuration);
        var sessions = new SqlSessionTemplate(factory.getObject());
        tx = new TransactionTemplate(new DataSourceTransactionManager(schema.source));
        var people = mock(FadadaPersonalIdentityService.class);
        when(people.requireCurrentVerified()).thenReturn(new PersonalIdentityRespDTO(
                true, "VERIFIED", "已认证", "authorized", "identified", "", "测试申请人", "face", "", "", "", ""));
        identities = sessions.getMapper(FadadaCorpIdentityMapper.class);
        service = new CompanyOnboardingAttemptService(sessions.getMapper(CompanyMapper.class),
                sessions.getMapper(CompanyOnboardingAttemptMapper.class), sessions.getMapper(CompanyMemberMapper.class),
                identities,
                sessions.getMapper(CompanyCertificationApplicationMapper.class), people);
        certifications = new CompanyCertificationServiceImpl(sessions.getMapper(CompanyMapper.class),
                sessions.getMapper(CompanyMemberMapper.class), sessions.getMapper(CompanyCertificationApplicationMapper.class),
                mock(AccessControlService.class), mock(TenantBootstrapService.class), mock(AuditLogService.class), false, "");
    }

    @AfterAll static void closeDatabase() {
        AuthContext.clear();
        if (schema != null) schema.close();
    }

    @ParameterizedTest @ValueSource(strings = {"identity", "legacy"})
    void migrationPreservesExistingApplicantAndProviderEvidenceWithoutManufacturingNewApplications(String layout) {
        try (var upgraded = new Schema(layout)) {
            upgraded.flyway(layout.equals("identity") ? "4" : "44").migrate();
            upgraded.jdbc.update("""
                    INSERT INTO company(id,name,credit_code,legal_person_name,created_by,certification_status)
                    VALUES (3,'迁移中的企业','MIGRATION-CREDIT','原法人',7,'PENDING_REVIEW')
                    """);
            upgraded.jdbc.update("""
                    INSERT INTO fadada_corp_identity(id,company_id,applicant_user_id,client_corp_id,open_corp_id,
                        local_status,auth_scopes,provider_request_id,submitted_at)
                    VALUES (4,3,7,'legacy-company-3','provider-company-3','IN_PROGRESS','["ident_info"]',
                        'original-provider-request','2020-01-01 10:00:00')
                    """);
            var before = upgraded.jdbc.queryForMap("""
                    SELECT applicant_user_id,client_corp_id,open_corp_id,local_status,auth_scopes,
                           provider_request_id,submitted_at FROM fadada_corp_identity WHERE id=4
                    """);
            // Exercise only the onboarding migration; later unrelated migrations have their own fixtures.
            var flyway = upgraded.flyway(layout.equals("identity") ? "5" : "45");
            assertThat(flyway.migrate().migrationsExecuted).isOne();
            flyway.validate();
            assertThat(upgraded.jdbc.queryForMap("""
                    SELECT applicant_user_id,client_corp_id,open_corp_id,local_status,auth_scopes,
                           provider_request_id,submitted_at FROM fadada_corp_identity WHERE id=4
                    """)).isEqualTo(before);
            assertThat(upgraded.jdbc.queryForObject("SELECT COUNT(*) FROM company_onboarding_attempt", Long.class)).isZero();
            assertThat(upgraded.jdbc.queryForObject("SELECT onboarding_attempt_id FROM fadada_corp_identity WHERE id=4", Long.class)).isNull();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test void differentApplicantsOwnSeparateSnapshotsAndRepeatedDraftIsIdempotent() {
        long company = company("PENDING", 7);
        var first = as(7, () -> service.draft(company, 7, request(company, "第一申请资料", "甲法人")));
        var repeated = as(7, () -> service.draft(company, 7, request(company, "第一申请补充资料", "甲法人")));
        var second = as(8, () -> service.draft(company, 8, request(company, "第二申请资料", "乙法人")));

        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getProviderRequestId()).isNotEqualTo(first.getProviderRequestId());
        assertThat(service.requireAttempt(company, 7).getCompanyName()).isEqualTo("第一申请补充资料");
        assertThat(service.requireAttempt(company, 8).getCompanyName()).isEqualTo("第二申请资料");
        assertThat(service.list(7)).filteredOn(a -> a.getCompanyId() == company)
                .extracting(CompanyOnboardingAttemptDO::getApplicantUserId).containsExactly(7L);
        assertThat(service.list(8)).filteredOn(a -> a.getCompanyId() == company)
                .extracting(CompanyOnboardingAttemptDO::getApplicantUserId).containsExactly(8L);
        assertThat(schema.jdbc.queryForObject("SELECT name FROM company WHERE id=?", String.class, company))
                .isEqualTo("待入驻企业" + company);
        assertThat(schema.jdbc.queryForObject("SELECT COUNT(*) FROM company_member WHERE company_id=? AND status='ACTIVE'", Long.class, company))
                .isZero();
        as(7, () -> service.draft(company, 7,
                new CompanySubmitReqVO(Long.toString(company), "本人清空可选资料", "CREDIT-" + company, "甲法人")));
        assertThat(schema.jdbc.queryForMap("""
                SELECT registered_address,contact_phone,bank_name,bank_account
                FROM company_onboarding_attempt WHERE id=?
                """, first.getId())).containsEntry("registered_address", null).containsEntry("contact_phone", null)
                .containsEntry("bank_name", null).containsEntry("bank_account", null);
        assertThat(service.requireAttempt(company, 8).getBankAccount()).isEqualTo("TEST-ACCOUNT");
    }

    @Test void twoApplicantsCompeteForOneSlotAndRetriesDoNotExtendItsThirtyMinuteDeadline() throws Exception {
        long company = company("PENDING", 7);
        as(7, () -> service.draft(company, 7, request(company, "甲资料", "甲法人")));
        as(8, () -> service.draft(company, 8, request(company, "乙资料", "乙法人")));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> contend(start, company, 7));
            var second = pool.submit(() -> contend(start, company, 8));
            start.countDown();
            long one = first.get(20, TimeUnit.SECONDS), two = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(one, two)).filteredOn(id -> id > 0).hasSize(1);
            long winner = one > 0 ? one : two;
            var current = service.current(company);
            assertThat(current.getApplicantUserId()).isEqualTo(winner);
            assertThat(Duration.between(current.getStartedAt(), current.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
            var retry = as(winner, () -> service.activate(company, winner));
            assertThat(retry.changed()).isFalse();
            assertThat(retry.attempt().getExpiresAt()).isEqualTo(current.getExpiresAt());
            assertThat(schema.jdbc.queryForObject("SELECT COUNT(*) FROM company_onboarding_attempt WHERE company_id=? AND status='ACTIVE'", Long.class, company))
                    .isOne();
        } finally { pool.shutdownNow(); }
    }

    @Test void expiredSlotCanBeTakenByAnotherApplicantAndLateOldCompletionCannotOverwriteIt() {
        long company = company("PENDING", 7);
        as(7, () -> service.draft(company, 7, request(company, "甲资料", "甲法人")));
        as(8, () -> service.draft(company, 8, request(company, "乙资料", "乙法人")));
        var old = as(7, () -> service.activate(company, 7)).attempt();
        schema.jdbc.update("""
                UPDATE fadada_corp_identity SET verified_name='旧名字',verified_legal_rep_name='旧法人',
                    operator_id='old-operator',operator_type='agent',ident_method='agent' WHERE company_id=?
                """, company);
        expire(old.getId());
        var replacement = as(8, () -> service.activate(company, 8));
        assertThat(replacement.changed()).isTrue();
        assertThat(service.ownsCurrent(company, 8)).isTrue();
        assertThat(service.ownsCurrent(company, 7)).isFalse();
        assertThatThrownBy(() -> inTransaction(() -> service.complete(company, 7, old.getProviderRequestId())))
                .isInstanceOf(BusinessException.class);
        assertThat(service.current(company).getId()).isEqualTo(replacement.attempt().getId());
        assertThat(service.current(company).getApplicantUserId()).isEqualTo(8);
        assertThat(schema.jdbc.queryForObject("SELECT name FROM company WHERE id=?", String.class, company))
                .isEqualTo("待入驻企业" + company);
        assertThat(service.requireAttempt(company, 8).getCompanyName()).isEqualTo("乙资料");
        assertThat(schema.jdbc.queryForMap("""
                SELECT applicant_user_id,onboarding_attempt_id,verified_name,verified_legal_rep_name,
                       operator_id,operator_type,ident_method FROM fadada_corp_identity WHERE company_id=?
                """, company)).containsEntry("applicant_user_id", 8L)
                .containsEntry("onboarding_attempt_id", replacement.attempt().getId())
                .containsEntry("verified_name", null).containsEntry("verified_legal_rep_name", null)
                .containsEntry("operator_id", null).containsEntry("operator_type", null).containsEntry("ident_method", null);
        assertThat(schema.jdbc.queryForObject("SELECT created_by FROM company WHERE id=?", Long.class, company)).isEqualTo(7);
    }

    @Test void waitingApplicantReadsTheNewAttemptAfterAnEarlierRepeatableReadSnapshot() throws Exception {
        long company = company("PENDING", 7);
        as(7, () -> service.draft(company, 7, request(company, "甲资料", "甲法人")));
        as(8, () -> service.draft(company, 8, request(company, "乙资料", "乙法人")));
        var old = as(7, () -> service.activate(company, 7)).attempt();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var snapshotRead = new CountDownLatch(1);
            var waiting = tx.execute(status -> {
                schema.jdbc.queryForObject("SELECT id FROM company WHERE id=? FOR UPDATE", Long.class, company);
                var reader = pool.submit(() -> tx.execute(readStatus -> {
                    assertThat(schema.jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                            .isEqualTo("REPEATABLE-READ");
                    assertThat(schema.jdbc.queryForObject("SELECT onboarding_attempt_id FROM fadada_corp_identity WHERE company_id=?",
                            Long.class, company)).isEqualTo(old.getId());
                    snapshotRead.countDown();
                    return service.current(company);
                }));
                try { assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
                expire(old.getId());
                as(8, () -> service.activate(company, 8));
                assertThat(reader.isDone()).isFalse();
                return reader;
            });
            assertThat(waiting.get(20, TimeUnit.SECONDS).getApplicantUserId()).isEqualTo(8);
        } finally { pool.shutdownNow(); }
    }

    @Test void completionMustMatchBothCurrentApplicantAndProviderRequest() {
        long company = company("PENDING", 7);
        as(7, () -> service.draft(company, 7, request(company, "本次资料", "法人")));
        var attempt = as(7, () -> service.activate(company, 7)).attempt();
        assertThatThrownBy(() -> inTransaction(() -> service.complete(company, 8, attempt.getProviderRequestId())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inTransaction(() -> service.complete(company, 7, "wrong-provider-request")))
                .isInstanceOf(BusinessException.class);
        assertThat(service.current(company).getId()).isEqualTo(attempt.getId());
        inTransaction(() -> service.complete(company, 7, attempt.getProviderRequestId()));
        assertThat(schema.jdbc.queryForObject("SELECT status FROM company_onboarding_attempt WHERE id=?", String.class, attempt.getId()))
                .isEqualTo("COMPLETED");
        assertThat(service.ownsCurrent(company, 7)).isFalse();
    }

    @Test void cancellingAnotherApplicantsDraftLeavesTheCurrentProviderSlotIntact() {
        long company = company("PENDING", 7);
        as(7, () -> service.draft(company, 7, request(company, "甲资料", "甲法人")));
        var second = as(8, () -> service.draft(company, 8, request(company, "乙资料", "乙法人")));
        var active = as(7, () -> service.activate(company, 7)).attempt();
        as(8, () -> { service.cancel(company, 8); return null; });
        assertThat(service.current(company).getId()).isEqualTo(active.getId());
        assertThat(service.ownsCurrent(company, 7)).isTrue();
        assertThat(schema.jdbc.queryForObject("SELECT status FROM company_onboarding_attempt WHERE id=?", String.class, second.getId()))
                .isEqualTo("CANCELLED");
        as(7, () -> { service.cancel(company, 7); return null; });
        assertThat(service.ownsCurrent(company, 7)).isFalse();
        var retry = as(8, () -> service.draft(company, 8, request(company, "乙重新申请", "乙法人")));
        assertThat(retry.getId()).isNotEqualTo(second.getId());
        assertThat(as(8, () -> service.activate(company, 8)).changed()).isTrue();
    }

    @Test void recentLegacyProcessingIsProtectedButAnUndatedOldRecordDoesNotBlockTheNextApplicant() {
        long recent = company("PENDING", 7);
        legacyIdentity(recent, 7, LocalDateTime.now().minusMinutes(5));
        as(8, () -> service.draft(recent, 8, request(recent, "乙资料", "乙法人")));
        assertThatThrownBy(() -> as(8, () -> service.activate(recent, 8))).isInstanceOf(BusinessException.class);

        long undated = company("PENDING", 7);
        legacyIdentity(undated, 7, null);
        as(8, () -> service.draft(undated, 8, request(undated, "新资料", "新法人")));
        assertThat(as(8, () -> service.activate(undated, 8)).changed()).isTrue();
        assertThat(service.current(undated).getApplicantUserId()).isEqualTo(8);
    }

    @Test void cancelledLegacyCompanyAllowsANewApplicantWithoutDeletingItsProviderAssociation() {
        long company = company("CANCELLED", 7);
        legacyIdentity(company, 7, LocalDateTime.now().minusDays(10));
        schema.jdbc.update("""
                UPDATE fadada_corp_identity SET local_status='CANCELLED',open_corp_id='existing-provider-company'
                WHERE company_id=?
                """, company);
        var own = as(8, () -> service.draft(company, 8, request(company, "本次企业资料", "法人")));

        as(8, () -> service.activate(company, 8));

        assertThat(service.current(company).getId()).isEqualTo(own.getId());
        assertThat(schema.jdbc.queryForMap("""
                SELECT applicant_user_id,authorization_user_id,client_corp_id,open_corp_id,local_status
                FROM fadada_corp_identity WHERE company_id=?
                """, company)).containsEntry("applicant_user_id", 8L).containsEntry("authorization_user_id", 8L)
                .containsEntry("client_corp_id", "legacy-" + company)
                .containsEntry("open_corp_id", "existing-provider-company").containsEntry("local_status", "NOT_STARTED");
        assertThat(schema.jdbc.queryForMap("SELECT created_by,certification_status FROM company WHERE id=?", company))
                .containsEntry("created_by", 7L).containsEntry("certification_status", "PENDING");
        assertThat(schema.jdbc.queryForObject("SELECT COUNT(*) FROM company_member WHERE company_id=? AND status='ACTIVE'",
                Long.class, company)).isZero();
    }

    @Test void adoptingProviderBindingPreservesApplicationOwnershipAndSurvivesANewApplicantsActivation() {
        long company = company("PENDING", 7);
        as(7, () -> service.draft(company, 7, request(company, "本人申请资料", "甲法人")));
        var original = as(7, () -> service.activate(company, 7)).attempt();
        long identity = identityId(company);
        String localClient = "tradepass-company-" + company;
        String providerClient = "provider-existing-client-" + company;
        String providerOpen = "provider-existing-open-" + company;
        var ownership = schema.jdbc.queryForMap("""
                SELECT i.company_id,i.applicant_user_id,i.onboarding_attempt_id,i.provider_request_id,c.created_by
                FROM fadada_corp_identity i JOIN company c ON c.id=i.company_id WHERE i.id=?
                """, identity);

        assertThat(as(7, () -> identities.adoptProviderBinding(identity, company, localClient, providerClient, providerOpen)).intValue()).isOne();

        assertThat(schema.jdbc.queryForMap("""
                SELECT i.company_id,i.applicant_user_id,i.onboarding_attempt_id,i.provider_request_id,c.created_by
                FROM fadada_corp_identity i JOIN company c ON c.id=i.company_id WHERE i.id=?
                """, identity)).isEqualTo(ownership);
        assertThat(schema.jdbc.queryForMap("SELECT client_corp_id,open_corp_id FROM fadada_corp_identity WHERE id=?", identity))
                .containsEntry("client_corp_id", providerClient).containsEntry("open_corp_id", providerOpen);
        assertThat(service.current(company).getId()).isEqualTo(original.getId());
        assertThat(service.current(company).getProviderRequestId()).isEqualTo(original.getProviderRequestId());

        as(7, () -> { service.cancel(company, 7); return null; });
        as(8, () -> service.draft(company, 8, request(company, "下一申请人资料", "乙法人")));
        var replacement = as(8, () -> service.activate(company, 8)).attempt();

        assertThat(replacement.getId()).isNotEqualTo(original.getId());
        assertThat(replacement.getProviderRequestId()).isNotEqualTo(original.getProviderRequestId());
        assertThat(identityId(company)).isEqualTo(identity);
        assertThat(schema.jdbc.queryForMap("""
                SELECT company_id,applicant_user_id,onboarding_attempt_id,provider_request_id,client_corp_id,open_corp_id
                FROM fadada_corp_identity WHERE id=?
                """, identity)).containsEntry("company_id", company).containsEntry("applicant_user_id", 8L)
                .containsEntry("onboarding_attempt_id", replacement.getId())
                .containsEntry("provider_request_id", replacement.getProviderRequestId())
                .containsEntry("client_corp_id", providerClient).containsEntry("open_corp_id", providerOpen);
        assertThat(schema.jdbc.queryForObject("SELECT created_by FROM company WHERE id=?", Long.class, company)).isEqualTo(7L);
        assertThat(schema.jdbc.queryForObject("SELECT status FROM company_onboarding_attempt WHERE id=?", String.class, original.getId()))
                .isEqualTo("CANCELLED");
    }

    @Test void concurrentCompaniesCannotAdoptTheSameProviderClientOrOpenIdentifier() throws Exception {
        long firstCompany = company("PENDING", 7), secondCompany = company("PENDING", 8);
        as(7, () -> service.draft(firstCompany, 7, request(firstCompany, "甲企业资料", "甲法人")));
        as(7, () -> service.activate(firstCompany, 7));
        as(8, () -> service.draft(secondCompany, 8, request(secondCompany, "乙企业资料", "乙法人")));
        as(8, () -> service.activate(secondCompany, 8));
        String providerClient = "racing-provider-client-" + firstCompany;
        String providerOpen = "racing-provider-open-" + firstCompany;
        var pool = Executors.newFixedThreadPool(2);
        try {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> contendForProviderBinding(ready, start, firstCompany, 7, providerClient, providerOpen));
            var second = pool.submit(() -> contendForProviderBinding(ready, start, secondCompany, 8, providerClient, providerOpen));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long one = first.get(20, TimeUnit.SECONDS), two = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(one, two)).filteredOn(id -> id > 0).hasSize(1);
            long winner = one > 0 ? one : two;
            long loser = winner == firstCompany ? secondCompany : firstCompany;
            long losingIdentity = identityId(loser);
            long losingUser = loser == firstCompany ? 7 : 8;
            String losingClient = "tradepass-company-" + loser;
            var winningBinding = schema.jdbc.queryForMap("SELECT * FROM fadada_corp_identity WHERE company_id=?", winner);
            var losingBinding = schema.jdbc.queryForMap("SELECT * FROM fadada_corp_identity WHERE company_id=?", loser);
            assertThat(winningBinding).containsEntry("client_corp_id", providerClient).containsEntry("open_corp_id", providerOpen);
            assertThat(losingBinding).containsEntry("client_corp_id", losingClient).containsEntry("open_corp_id", null);
            assertThat(schema.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM fadada_corp_identity WHERE client_corp_id=? OR open_corp_id=?
                    """, Long.class, providerClient, providerOpen)).isOne();

            // Exercise the two unique indexes separately after the simultaneous collision.
            assertThatThrownBy(() -> as(losingUser, () -> identities.adoptProviderBinding(losingIdentity, loser,
                    losingClient, providerClient, "different-open-" + loser))).isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> as(losingUser, () -> identities.adoptProviderBinding(losingIdentity, loser,
                    losingClient, "different-client-" + loser, providerOpen))).isInstanceOf(DuplicateKeyException.class);
            assertThat(schema.jdbc.queryForMap("SELECT * FROM fadada_corp_identity WHERE company_id=?", winner)).isEqualTo(winningBinding);
            assertThat(schema.jdbc.queryForMap("SELECT * FROM fadada_corp_identity WHERE company_id=?", loser)).isEqualTo(losingBinding);
        } finally { pool.shutdownNow(); }
    }

    @Test void providerBindingCasRejectsAStaleClientOrAnAlreadyDifferentOpenIdentifier() {
        long company = company("PENDING", 7);
        as(7, () -> service.draft(company, 7, request(company, "本次资料", "法人")));
        as(7, () -> service.activate(company, 7));
        long identity = identityId(company);
        String localClient = "tradepass-company-" + company;
        String providerClient = "cas-provider-client-" + company;
        String providerOpen = "cas-provider-open-" + company;
        var original = schema.jdbc.queryForMap("SELECT * FROM fadada_corp_identity WHERE id=?", identity);

        assertThat(as(7, () -> identities.adoptProviderBinding(identity, company, "stale-client", providerClient, providerOpen)).intValue()).isZero();
        assertThat(schema.jdbc.queryForMap("SELECT * FROM fadada_corp_identity WHERE id=?", identity)).isEqualTo(original);
        assertThat(as(7, () -> identities.adoptProviderBinding(identity, company, localClient, providerClient, providerOpen)).intValue()).isOne();
        var adopted = schema.jdbc.queryForMap("SELECT * FROM fadada_corp_identity WHERE id=?", identity);

        assertThat(as(7, () -> identities.adoptProviderBinding(identity, company, localClient,
                "stale-replacement-client", providerOpen)).intValue()).isZero();
        assertThat(as(7, () -> identities.adoptProviderBinding(identity, company, providerClient,
                "replacement-client", "different-provider-open")).intValue()).isZero();
        assertThat(as(7, () -> identities.adoptProviderBinding(identity, company + 1, providerClient,
                "wrong-company-client", providerOpen)).intValue()).isZero();
        assertThat(schema.jdbc.queryForMap("SELECT * FROM fadada_corp_identity WHERE id=?", identity)).isEqualTo(adopted);
    }

    @Test void previouslyEstablishedCompaniesCannotBeTakenOverEvenIfTheirVisibleStatusWasRejected() {
        for (String evidence : List.of("company", "member", "identity", "verifiedAt", "application")) {
            long company = company(evidence.equals("company") ? "VERIFIED" : "REJECTED", 7);
            switch (evidence) {
                case "member" -> schema.jdbc.update("""
                        INSERT INTO company_member(id,company_id,user_id,role_code,status)
                        VALUES (?, ?,7,'ADMIN','ACTIVE')
                        """, IDS.incrementAndGet(), company);
                case "identity", "verifiedAt" -> {
                    legacyIdentity(company, 7, null);
                    schema.jdbc.update("UPDATE fadada_corp_identity SET local_status=?,verified_at=? WHERE company_id=?",
                            evidence.equals("identity") ? "VERIFIED" : "FAILED",
                            evidence.equals("verifiedAt") ? LocalDateTime.now().minusDays(1) : null, company);
                }
                case "application" -> schema.jdbc.update("""
                        INSERT INTO company_certification_application(id,company_id,applicant_user_id,provider_request_id,status)
                        VALUES (?, ?,7,?,'APPROVED')
                        """, IDS.incrementAndGet(), company, "approved-" + company);
                default -> { }
            }
            assertThatThrownBy(() -> as(8, () -> service.draft(company, 8, request(company, "新申请", "新人"))))
                    .as("Historical establishment evidence: %s", evidence).isInstanceOf(BusinessException.class);
            assertThat(schema.jdbc.queryForObject("SELECT created_by FROM company WHERE id=?", Long.class, company)).isEqualTo(7);
        }
    }

    @Test void independentLegalClaimCreatesANonMemberAndPreservesTheExistingAdministrator() {
        long company = company("VERIFIED", 7);
        schema.jdbc.update("""
                INSERT INTO company_member(id,company_id,user_id,role_code,role_codes,status,is_administrator,custom_permissions)
                VALUES (?,?,7,'ADMIN','["ADMIN"]','ACTIVE',1,'["company_manage"]')
                """, IDS.incrementAndGet(), company);
        var admin = schema.jdbc.queryForMap("SELECT * FROM company_member WHERE company_id=? AND user_id=7", company);

        as(9, () -> { certifications.requireLegalClaim(company, 9); return null; });
        assertThat(schema.jdbc.queryForObject("SELECT COUNT(*) FROM company_member WHERE company_id=? AND user_id=9",
                Long.class, company)).isZero();
        as(9, () -> { certifications.completeIndependentLegalClaim(company, 9, "legal-new-" + company); return null; });
        as(9, () -> { certifications.completeIndependentLegalClaim(company, 9, "legal-repeat-" + company); return null; });

        assertThat(schema.jdbc.queryForMap("""
                SELECT role_code,status,is_legal_person,is_administrator FROM company_member WHERE company_id=? AND user_id=9
                """, company)).containsEntry("role_code", "LEGAL").containsEntry("status", "ACTIVE")
                .containsEntry("is_legal_person", true).containsEntry("is_administrator", false);
        assertThat(schema.jdbc.queryForMap("SELECT * FROM company_member WHERE company_id=? AND user_id=7", company)).isEqualTo(admin);
        assertThat(schema.jdbc.queryForObject("SELECT COUNT(*) FROM company_certification_application WHERE company_id=?",
                Long.class, company)).isOne();
    }

    @Test void explicitLegalProofActivatesAnExistingCandidateAndKeepsTheirBusinessPermissions() {
        long company = company("VERIFIED", 7);
        schema.jdbc.update("""
                INSERT INTO company_member(id,company_id,user_id,role_code,role_codes,status,is_administrator,custom_permissions)
                VALUES (?,?,9,'LEGAL_CANDIDATE','["LEGAL_CANDIDATE","FINANCE"]','PENDING',0,'["invoice_view"]')
                """, IDS.incrementAndGet(), company);

        as(9, () -> { certifications.completeIndependentLegalClaim(company, 9, "legal-pending-" + company); return null; });

        assertThat(schema.jdbc.queryForMap("""
                SELECT role_code,status,is_legal_person,custom_permissions,
                    JSON_CONTAINS(role_codes,JSON_QUOTE('FINANCE')) AS keeps_finance,
                    JSON_CONTAINS(role_codes,JSON_QUOTE('LEGAL_CANDIDATE')) AS keeps_candidate
                FROM company_member WHERE company_id=? AND user_id=9
                """, company)).containsEntry("role_code", "LEGAL").containsEntry("status", "ACTIVE")
                .containsEntry("is_legal_person", true).containsEntry("custom_permissions", "[\"invoice_view\"]")
                .containsEntry("keeps_finance", 1L).containsEntry("keeps_candidate", 0L);
    }

    @Test void simultaneousIndependentClaimsCannotCreateTwoActiveLegalRepresentatives() throws Exception {
        long company = company("VERIFIED", 7);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> contendForLegalClaim(ready, start, company, 8));
            var second = pool.submit(() -> contendForLegalClaim(ready, start, company, 9));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .filteredOn(user -> user > 0).hasSize(1);
            assertThat(schema.jdbc.queryForObject("""
                    SELECT COUNT(*) FROM company_member WHERE company_id=? AND status='ACTIVE'
                        AND (role_code='LEGAL' OR is_legal_person=1 OR JSON_CONTAINS(role_codes,JSON_QUOTE('LEGAL')))
                    """, Long.class, company)).isOne();
            assertThat(schema.jdbc.queryForObject("SELECT COUNT(*) FROM company_certification_application WHERE company_id=?",
                    Long.class, company)).isOne();
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private static long contendForLegalClaim(CountDownLatch ready, CountDownLatch start, long company, long user)
            throws InterruptedException {
        ready.countDown(); start.await();
        try { return as(user, () -> { certifications.completeIndependentLegalClaim(company, user, "legal-race-" + company + "-" + user); return user; }); }
        catch (BusinessException occupied) { return 0; }
    }

    private static long contend(CountDownLatch start, long company, long user) throws InterruptedException {
        start.await();
        try { return as(user, () -> service.activate(company, user)).changed() ? user : 0; }
        catch (BusinessException expectedBusySlot) { return 0; }
    }

    private static long contendForProviderBinding(CountDownLatch ready, CountDownLatch start, long company,
                                                  long user, String providerClient, String providerOpen) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return as(user, () -> identities.adoptProviderBinding(identityId(company), company,
                    "tradepass-company-" + company, providerClient, providerOpen)) == 1 ? company : 0;
        } catch (DuplicateKeyException expectedCollision) { return 0; }
    }

    private static long identityId(long company) {
        return schema.jdbc.queryForObject("SELECT id FROM fadada_corp_identity WHERE company_id=?", Long.class, company);
    }

    private static long company(String status, long creator) {
        long id = IDS.incrementAndGet();
        schema.jdbc.update("""
                INSERT INTO company(id,name,credit_code,legal_person_name,created_by,certification_status)
                VALUES (?,?,?,'原法人',?,?)
                """, id, "待入驻企业" + id, "CREDIT-" + id, creator, status);
        return id;
    }

    private static CompanySubmitReqVO request(long company, String name, String legalName) {
        return new CompanySubmitReqVO(Long.toString(company), name, "CREDIT-" + company, legalName,
                "测试地址", "18800000000", "测试银行", "TEST-ACCOUNT");
    }

    private static void legacyIdentity(long company, long applicant, LocalDateTime submittedAt) {
        schema.jdbc.update("""
                INSERT INTO fadada_corp_identity(id,company_id,applicant_user_id,client_corp_id,
                    local_status,auth_scopes,provider_request_id,submitted_at)
                VALUES (?,?,?,?,'IN_PROGRESS','[]',?,?)
                """, IDS.incrementAndGet(), company, applicant, "legacy-" + company, "legacy-request-" + company, submittedAt);
    }

    private static void expire(long attempt) {
        schema.jdbc.update("UPDATE company_onboarding_attempt SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=?", attempt);
    }

    private static <T> T as(long user, Supplier<T> work) {
        AuthContext.set(user, null);
        try { return tx.execute(status -> work.get()); }
        finally { AuthContext.clear(); }
    }

    private static void inTransaction(Runnable work) { tx.executeWithoutResult(status -> work.run()); }

    private static final class Schema implements AutoCloseable {
        private final JdbcTemplate admin;
        private final String database;
        private final DriverManagerDataSource source;
        private final JdbcTemplate jdbc;
        private final String location;

        private Schema(String layout) {
            String base = System.getProperty("tradepass.test.mysql.url");
            if (base == null || !base.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
                throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
            String user = System.getProperty("tradepass.test.mysql.username", "root");
            String password = System.getProperty("tradepass.test.mysql.password", "");
            admin = new JdbcTemplate(new DriverManagerDataSource(base, user, password));
            database = "tradepass_fix_validation_onboard_" + layout + "_" + Long.toUnsignedString(System.nanoTime());
            admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
            source = new DriverManagerDataSource(base.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), user, password);
            jdbc = new JdbcTemplate(source);
            String directory = layout.equals("legacy") ? "sql/mysql"
                    : "tradepass-module-identity/tradepass-module-identity-server/src/main/resources/db/owned/identity";
            location = "filesystem:" + RepoRoot.find().resolve(directory).toAbsolutePath();
        }

        private Flyway flyway(String target) {
            var configured = Flyway.configure().dataSource(source).locations(location);
            return (target == null ? configured : configured.target(target)).load();
        }

        @Override public void close() { admin.execute("DROP DATABASE `" + database + "`"); }
    }
}
