package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import static com.tradepass.framework.common.exception.SigningErrorCodes.*;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.framework.fadada.core.FadadaCompanyGateway;
import com.tradepass.framework.fadada.core.FadadaCompanyQueryException;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.service.permission.AccessControlService;
import org.springframework.stereotype.Service;

/** Synchronizes ordinary signing members using the person's verified provider account. */
@Service
public class FadadaSigningMemberService {
    private static final java.util.List<String> SIGNING_SCOPES = java.util.List.of(
            "ident_info", "seal_info", "signtask_init", "signtask_info", "signtask_file");
    private final AccessControlService accessControl;
    private final FadadaCompanyService companyService;
    private final FadadaPersonalIdentityService personalService;
    private final FadadaCompanyGateway gateway;
    private final FadadaProperties properties;

    public FadadaSigningMemberService(AccessControlService accessControl, FadadaCompanyService companyService,
                                     FadadaPersonalIdentityService personalService, FadadaCompanyGateway gateway,
                                     FadadaProperties properties) {
        this.accessControl = accessControl;
        this.companyService = companyService;
        this.personalService = personalService;
        this.gateway = gateway;
        this.properties = properties;
    }

    public void requireCurrentSigningMember(long companyId) {
        ensureSigningMember(companyId, AuthContext.userId());
    }

    /** Resolves the target person, never the manager who happens to invoke this method. */
    public FadadaCompanyGateway.CompanyMember ensureSigningMember(long companyId, long userId) {
        if (AuthContext.userId() != userId) accessControl.requireManager(companyId);
        var result = synchronize(companyId, userId);
        requireActivated(result.member());
        return result.member();
    }

    /** Server-only desired-state reconciliation; never an API or an impersonated request. */
    FadadaCompanyGateway.CompanyMember reconcileLocalSigningMember(long companyId, long targetUserId) {
        // synchronize rechecks ACTIVE membership, effective signing permission and
        // the target's live personal identity before touching the provider company.
        var result = synchronize(companyId, targetUserId);
        requireActivated(result.member());
        return result.member();
    }

    /** Only the current person may receive their own first-join invitation URL. */
    public ServiceUrlPayload syncCurrent(long companyId) {
        var result = synchronize(companyId, AuthContext.userId());
        if ("activated".equals(result.member().memberStatus())) return new ServiceUrlPayload(null, "member", "READY");
        if ("inactive".equals(result.member().memberStatus())) {
            String url = gateway.createMemberActivationUrl(result.identity().getOpenCorpId(), result.member().memberId());
            return new ServiceUrlPayload(url, "member", "ACTIVATION_REQUIRED");
        }
        requireActivated(result.member());
        throw new BusinessException("电子签企业成员状态尚未确认，请稍后重新同步");
    }

    private MemberResolution synchronize(long companyId, long userId) {
        if (userId <= 0 || !accessControl.isActiveMember(companyId, userId)) throw new BusinessException(ADMIN_REVIEW_REQUIRED, "该账号不是企业的有效成员");
        var role = accessControl.effectiveRole(companyId, userId);
        if (role == null || role.permissions() == null
                || role.permissions().stream().noneMatch(value -> "contract_sign".equals(value) || "all".equals(value))) {
            throw new BusinessException(ADMIN_REVIEW_REQUIRED, "该成员尚未获得商签通合同签署权限，请企业管理员先分配签署权限");
        }
        var person = personalService.verifiedPerson(userId);
        if (person == null || !hasText(person.fddId()) || !hasText(person.openUserId())) {
            throw new BusinessException(PERSONAL_IDENTITY_REQUIRED, "当前个人实名信息尚未完整同步，请先完成个人认证后重试");
        }
        var identity = companyService.requireVerified(companyId);
        requireOrganizationAuthorization(identity);
        String internalIdentifier = "tradepass-user-" + userId;
        var linked = gateway.findMemberByInternalIdentifier(identity.getOpenCorpId(), internalIdentifier);
        if (linked != null) {
            requireLinkedIdentifier(linked, internalIdentifier);
            requireSamePerson(linked, person);
            return new MemberResolution(identity, linked);
        }
        var checked = new java.util.HashSet<Long>();
        FadadaCompanyGateway.CompanyMember existing = null;
        if (hasText(person.name())) existing = findPersonMember(identity.getOpenCorpId(), person, checked,
                gateway.listMemberIds(identity.getOpenCorpId(), person.name()));
        // Corporate display names can differ from the person's verified name. A
        // failed name search is not evidence that this person has not joined.
        if (existing == null) existing = findPersonMember(identity.getOpenCorpId(), person, checked,
                gateway.listMemberIds(identity.getOpenCorpId(), null));
        if (existing != null) return new MemberResolution(identity, existing);
        if (!hasText(person.name()) || !usableProviderAccount(person)) {
            throw new BusinessException(ADMIN_REVIEW_REQUIRED, "电子签实名账号尚未返回可关联的手机号或邮箱，请本人先在电子签平台绑定账号后重新同步；系统不会使用其他联系手机号代替");
        }
        FadadaCompanyGateway.MemberInvitation created;
        try {
            created = gateway.createMember(new FadadaCompanyGateway.MemberCreateCommand(identity.getOpenCorpId(),
                    internalIdentifier, person.name(), person.accountName()));
        } catch (RuntimeException uncertain) {
            // A timeout or duplicate response can follow a successful provider create.
            // Recover the same deterministic member; never issue another create here.
            FadadaCompanyGateway.CompanyMember recovered;
            try { recovered = gateway.findMemberByInternalIdentifier(identity.getOpenCorpId(), internalIdentifier); }
            catch (RuntimeException unavailable) { throw pendingCreation(); }
            if (recovered == null) throw pendingCreation();
            requireLinkedIdentifier(recovered, internalIdentifier);
            requireSamePerson(recovered, person);
            return new MemberResolution(identity, recovered);
        }
        if (created == null || created.memberId() == null || created.memberId() <= 0
                || !internalIdentifier.equals(created.internalIdentifier())) throw pendingCreation();
        var member = gateway.getMember(identity.getOpenCorpId(), created.memberId());
        if (member == null || !created.memberId().equals(member.memberId())) throw pendingCreation();
        requireLinkedIdentifier(member, internalIdentifier);
        requireSamePerson(member, person);
        return new MemberResolution(identity, member);
    }

    private FadadaCompanyGateway.CompanyMember findPersonMember(String openCorpId,
                                        FadadaPersonalIdentityService.VerifiedPerson person,
                                        java.util.Set<Long> checked, java.util.List<Long> candidates) {
        for (Long memberId : candidates) {
            if (memberId == null || memberId <= 0) throw new BusinessException("电子签企业成员信息不完整，请稍后重试");
            if (!checked.add(memberId)) continue;
            var member = gateway.getMember(openCorpId, memberId);
            if (member == null || !memberId.equals(member.memberId())) {
                throw new BusinessException("电子签企业成员信息不一致，请稍后重试");
            }
            if (person.fddId().equals(member.fddId()) || accountMatches(member, person)) {
                requireSamePerson(member, person);
                return member;
            }
        }
        return null;
    }

    private void requireSamePerson(FadadaCompanyGateway.CompanyMember member,
                                   FadadaPersonalIdentityService.VerifiedPerson person) {
        if (person.fddId().equals(member.fddId())) return;
        // An unactivated invitation may not yet expose fddId. Only the same live
        // provider account can receive that invitation; signing still requires fddId.
        if (!hasText(member.fddId()) && "inactive".equals(member.memberStatus())
                && accountMatches(member, person)) return;
        throw new BusinessException(ADMIN_REVIEW_REQUIRED, "电子签企业成员与该账号的个人实名标识不一致，请联系企业管理员核验，系统不会覆盖既有成员");
    }

    private boolean accountMatches(FadadaCompanyGateway.CompanyMember member,
                                   FadadaPersonalIdentityService.VerifiedPerson person) {
        return usableProviderAccount(person) && member.accountNames() != null && member.accountNames().stream()
                .anyMatch(value -> person.accountName().equalsIgnoreCase(value));
    }

    private void requireLinkedIdentifier(FadadaCompanyGateway.CompanyMember member, String internalIdentifier) {
        if (!internalIdentifier.equals(member.internalIdentifier())) {
            throw new BusinessException(ADMIN_REVIEW_REQUIRED, "电子签企业成员关联标识不一致，请联系企业管理员核验");
        }
    }

    private void requireActivated(FadadaCompanyGateway.CompanyMember member) {
        if ("activated".equals(member.memberStatus())) return;
        if ("disabled".equals(member.memberStatus())) {
            throw new BusinessException(ADMIN_REVIEW_REQUIRED, "该成员已在电子签平台被禁用，请企业管理员核验；系统不会自动重新加入或恢复禁用成员");
        }
        if ("inactive".equals(member.memberStatus())) {
            throw new BusinessException(MEMBER_ACTIVATION_REQUIRED, "成员已同步，请本人确认加入电子签企业后继续签署，无需企业超管重复审批");
        }
        throw new BusinessException("电子签企业成员状态尚未确认，请稍后重新同步");
    }

    private boolean usableAccount(String accountName) {
        return hasText(accountName) && (accountName.matches("\\+?[0-9]{6,20}")
                || accountName.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"));
    }

    private boolean usableProviderAccount(FadadaPersonalIdentityService.VerifiedPerson person) {
        // The identity API returns fddId in accountName when no email/mobile is bound.
        // A numeric FDD identifier must not be mistaken for a phone number.
        return !person.fddId().equals(person.accountName()) && usableAccount(person.accountName());
    }

    private BusinessException pendingCreation() {
        return new BusinessException("电子签成员同步结果待确认，请稍后重新同步；系统将先核对原成员关联，不会重复创建");
    }

    private record MemberResolution(FadadaCorpIdentityDO identity, FadadaCompanyGateway.CompanyMember member) {}

    public ServiceUrlPayload createOrganizationManageUrl(long companyId) {
        accessControl.requireManager(companyId);
        var identity = companyService.requireVerified(companyId);
        requireOrganizationAuthorization(identity);
        String url = gateway.createOrganizationManageUrl(identity.getOpenCorpId(), "tradepass-user-" + AuthContext.userId());
        return new ServiceUrlPayload(url, "organization", "MANAGE");
    }

    private void requireOrganizationAuthorization(FadadaCorpIdentityDO identity) {
        FadadaCompanyGateway.CompanyAccount account;
        try {
            account = gateway.getCompany(identity.getClientCorpId(), identity.getOpenCorpId());
        } catch (FadadaCompanyQueryException unavailable) {
            if (!java.util.Set.of("210002", "210032").contains(unavailable.providerCode())) throw unavailable;
            throw new BusinessException(COMPANY_AUTHORIZATION_REQUIRED,
                    "企业成员核验授权尚未确认，请企业电子签超管补充组织数据授权后继续签署");
        }
        if (account == null || !identity.getOpenCorpId().equals(account.openCorpId())
                || (hasText(account.clientCorpId()) && !identity.getClientCorpId().equals(account.clientCorpId())
                    && !properties.matchesOwnerEnterprise(account.openCorpId(), account.clientCorpId(),
                            identity.getVerifiedCreditCode()))
                || !"enable".equals(account.availableStatus()) || !"identified".equals(account.identStatus())
                || !"authorized".equals(account.bindingStatus())) {
            throw new BusinessException(COMPANY_AUTHORIZATION_REQUIRED, "企业电子签授权状态已变化，请由企业管理员重新核对企业认证");
        }
        if (account.authScopes() == null || !account.authScopes().contains("organization")) {
            throw new BusinessException(COMPANY_AUTHORIZATION_REQUIRED, "企业尚未授权电子签成员核验，请企业法大大超管补充组织数据授权后继续签署");
        }
        if (!account.authScopes().containsAll(SIGNING_SCOPES)) {
            throw new BusinessException(COMPANY_AUTHORIZATION_REQUIRED, "企业电子签业务授权不完整，请企业法大大超管重新核对企业认证授权");
        }
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
