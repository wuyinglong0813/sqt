package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.bean.base.BaseReq;
import com.fasc.open.api.bean.common.CorpIdentInfo;
import com.fasc.open.api.exception.ApiException;
import com.fasc.open.api.v5_1.client.CorpClient;
import com.fasc.open.api.v5_1.client.OpenApiClient;
import com.fasc.open.api.v5_1.client.OrgClient;
import com.fasc.open.api.v5_1.req.org.GetCorpMemberDetailReq;
import com.fasc.open.api.v5_1.req.org.GetMemberListReq;
import com.fasc.open.api.v5_1.req.org.GetMemberListFilter;
import com.fasc.open.api.v5_1.req.org.GetCorpOrgManageUrlReq;
import com.fasc.open.api.v5_1.req.org.CreateCorpMemberReq;
import com.fasc.open.api.v5_1.req.org.GetCorpMemberActiveUrlReq;
import com.fasc.open.api.v5_1.res.org.EmployeeInfo;
import com.fasc.open.api.v5_1.client.SealClient;
import com.fasc.open.api.v5_1.req.corp.GetCorpAuthResourceUrlReq;
import com.fasc.open.api.v5_1.req.corp.CorpIdentInfoReq;
import com.fasc.open.api.v5_1.req.corp.GetCorpIdentityInfoReq;
import com.fasc.open.api.v5_1.req.corp.GetCorpReq;
import com.fasc.open.api.v5_1.req.corp.GetChangeCorpIdentityInfoUrlReq;
import com.fasc.open.api.v5_1.req.seal.GetSealInfoListReq;
import com.fasc.open.api.v5_1.req.seal.GetSealManageUrlReq;
import com.fasc.open.api.v5_1.res.common.ECorpAuthUrlRes;
import com.fasc.open.api.v5_1.res.corp.CorpIdentityInfoRes;
import com.fasc.open.api.v5_1.res.corp.CorpRes;
import com.fasc.open.api.v5_1.res.seal.GetSealInfoListRes;
import com.fasc.open.api.v5_1.res.seal.GetSealManageUrlRes;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Objects;

@Component
public class SdkFadadaCompanyGateway implements FadadaCompanyGateway {
    private static final Logger log = LoggerFactory.getLogger(SdkFadadaCompanyGateway.class);
    private final CorpClient corpClient;
    private final SealClient sealClient;
    private final OrgClient orgClient;
    private final FadadaAccessTokenProvider tokenProvider;
    private final LegacyFadadaCompanyIdentityClient legacyIdentityClient;

    @Autowired
    public SdkFadadaCompanyGateway(FadadaProperties properties, FadadaAccessTokenProvider tokenProvider) {
        this(properties, tokenProvider, new LegacyFadadaCompanyIdentityClient(properties, tokenProvider));
    }

    SdkFadadaCompanyGateway(FadadaProperties properties, FadadaAccessTokenProvider tokenProvider,
                           LegacyFadadaCompanyIdentityClient legacyIdentityClient) {
        OpenApiClient client = new OpenApiClient(
                properties.getAppId(), properties.getAppSecret(), properties.getServerUrl());
        this.corpClient = new CorpClient(client);
        this.sealClient = new SealClient(client);
        this.orgClient = new OrgClient(client);
        this.tokenProvider = tokenProvider;
        this.legacyIdentityClient = legacyIdentityClient;
    }

    @Override
    public String createAuthUrl(AuthCommand command) {
        GetCorpAuthResourceUrlReq request = new GetCorpAuthResourceUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setClientCorpId(command.clientCorpId());
        request.setClientUserId(command.clientUserId());
        // Prefill the current operator's bound phone; the provider still verifies the login.
        if (hasText(command.accountName())) request.setAccountName(command.accountName());
        CorpIdentInfoReq company = new CorpIdentInfoReq();
        company.setCorpName(command.companyName());
        company.setCorpIdentNo(command.creditCode());
        if (command.legalRepresentativeOnly()) company.setCorpIdentMethod(List.of("legalRepFace"));
        request.setCorpIdentInfo(company);
        request.setCorpNonEditableInfo(List.of("corpName", "corpIdentNo"));
        request.setAuthScopes(command.authScopes());
        request.setCallbackUrl(command.callbackUrl());
        if (hasText(command.redirectMiniAppUrl())) request.setRedirectMiniAppUrl(command.redirectMiniAppUrl());
        ECorpAuthUrlRes response = invoke(request, () -> corpClient.getCorpAuthUrl(request), "获取企业认证地址");
        if (!hasText(response.getAuthUrl())) throw new BusinessException("未获取到企业认证地址，请稍后重试");
        return response.getAuthUrl();
    }

    @Override
    public CompanyAccount getCompany(String clientCorpId, String openCorpId) {
        GetCorpReq request = new GetCorpReq();
        request.setAccessToken(tokenProvider.get());
        applyCompanyLookup(request, clientCorpId, openCorpId);
        CorpRes response = invoke(request, () -> corpClient.get(request), "查询企业认证状态");
        return new CompanyAccount(response.getClientCorpId(), response.getOpenCorpId(),
                response.getBindingStatus(), response.getIdentStatus(), response.getAvailableStatus(),
                response.getAuthScope());
    }

    @Override
    public CompanyAccount getCompanyByCreditCode(String creditCode) {
        if (!hasText(creditCode)) throw new BusinessException("企业统一社会信用代码缺失");
        GetCorpReq request = new GetCorpReq();
        request.setAccessToken(tokenProvider.get());
        request.setCorpIdentNo(creditCode);
        CorpRes response = invoke(request, () -> corpClient.get(request), "查询企业认证状态");
        return new CompanyAccount(response.getClientCorpId(), response.getOpenCorpId(),
                response.getBindingStatus(), response.getIdentStatus(), response.getAvailableStatus(),
                response.getAuthScope());
    }

    @Override
    public CompanyIdentity getIdentity(String openCorpId) {
        GetCorpIdentityInfoReq request = new GetCorpIdentityInfoReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(openCorpId);
        CorpIdentityInfoRes response = invoke(request, () -> corpClient.getIdentityInfo(request), "查询企业认证信息");
        CompanyIdentity primary = toCompanyIdentity(response);
        // SDK fields alone do not document the operatorId namespace. A matching 5.0
        // result is needed even when the 5.1 extension already returned both fields.
        try {
            CompanyIdentity legacy = legacyIdentityClient.getIdentity(openCorpId);
            if (matchesIdentityVersion(openCorpId, primary, legacy)) return legacy;
            log.warn("Legacy enterprise identity lookup did not match the current identity version");
        } catch (RuntimeException exception) {
            log.warn("Legacy enterprise identity lookup unavailable; retaining current identity result");
        }
        return primary;
    }

    private static boolean matchesIdentityVersion(String requested, CompanyIdentity primary, CompanyIdentity legacy) {
        return legacy != null && legacy.operatorOpenUserIdDocumented()
                && Objects.equals(requested, primary.openCorpId()) && Objects.equals(requested, legacy.openCorpId())
                && "identified".equalsIgnoreCase(primary.identStatus()) && "identified".equalsIgnoreCase(legacy.identStatus())
                && sameNonBlank(primary.companyName(), legacy.companyName())
                && sameNonBlank(primary.creditCode(), legacy.creditCode())
                && sameNonBlank(primary.legalRepName(), legacy.legalRepName())
                && sameNonBlank(primary.identMethod(), legacy.identMethod())
                && sameNonBlank(primary.verifiedAt(), legacy.verifiedAt())
                && hasText(legacy.operatorId()) && hasText(legacy.operatorType())
                && (!hasText(primary.operatorId()) || primary.operatorId().equals(legacy.operatorId()))
                && (!hasText(primary.operatorType()) || primary.operatorType().equals(legacy.operatorType()));
    }

    private static boolean sameNonBlank(String first, String second) {
        return hasText(first) && hasText(second) && first.trim().equals(second.trim());
    }

    static CompanyIdentity toCompanyIdentity(CorpIdentityInfoRes response) {
        CorpIdentInfo info = response.getCorpIdentInfo();
        return new CompanyIdentity(response.getOpenCorpId(), response.getCorpIdentStatus(),
                info == null ? null : info.getCorpName(), info == null ? null : info.getCorpIdentNo(),
                info == null ? null : info.getLegalRepName(), response.getCorpIdentMethod(),
                response.getIdentSubmitTime(), response.getIdentSuccessTime(),
                response.getOperatorType(), response.getOperatorId());
    }

    @Override
    public CompanyMember getMember(String openCorpId, long memberId) {
        if (!hasText(openCorpId) || memberId <= 0) throw new BusinessException("企业成员标识缺失");
        GetCorpMemberDetailReq request = new GetCorpMemberDetailReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(openCorpId);
        request.setMemberId(memberId);
        var response = invoke(request, () -> orgClient.getCorpMemberDetail(request), "查询企业成员权限");
        var accounts = new java.util.LinkedHashSet<String>();
        if (response.getAccountNames() != null) response.getAccountNames().stream().filter(SdkFadadaCompanyGateway::hasText).forEach(accounts::add);
        if (hasText(response.getMemberMobile())) accounts.add(response.getMemberMobile());
        if (hasText(response.getMemberEmail())) accounts.add(response.getMemberEmail());
        return new CompanyMember(response.getMemberId(), response.getMemberStatus(),
                response.getRoleType(), response.getFddId(), response.getInternalIdentifier(), List.copyOf(accounts));
    }

    @Override
    public List<Long> listMemberIds(String openCorpId, String memberName) {
        return listMemberCandidates(openCorpId, memberName).stream().map(EmployeeInfo::getMemberId).toList();
    }

    private List<EmployeeInfo> listMemberCandidates(String openCorpId, String memberName) {
        if (!hasText(openCorpId)) throw new BusinessException("企业成员核验信息缺失");
        var members = new java.util.ArrayList<EmployeeInfo>();
        var seen = new java.util.HashSet<Long>();
        Integer total = null;
        Integer pageCount = null;
        for (int page = 1; page <= 1000; page++) {
            GetMemberListReq request = new GetMemberListReq();
            request.setAccessToken(tokenProvider.get());
            request.setOpenCorpId(openCorpId);
            // Name only narrows candidates. The caller must match the detail's fddId
            // to the current verified person; a name or phone is never identity proof.
            if (hasText(memberName)) {
                GetMemberListFilter filter = new GetMemberListFilter();
                filter.setMemberName(memberName);
                request.setListFilter(filter);
            }
            request.setListPageNo(page);
            request.setListPageSize(100);
            var response = invoke(request, () -> orgClient.getMemberList(request), "查询企业成员列表");
            var values = response.getEmployeeInfos();
            if (response.getListPageNo() == null || response.getListPageNo() != page
                    || response.getListPageCount() == null || response.getListPageCount() < 0
                    || response.getTotalCount() == null || response.getTotalCount() < 0
                    || values == null || response.getCountInPage() == null || response.getCountInPage() != values.size()
                    || (page < response.getListPageCount() && values.isEmpty())
                    || (total != null && !total.equals(response.getTotalCount()))
                    || (pageCount != null && !pageCount.equals(response.getListPageCount()))) {
                throw new BusinessException("电子签企业成员列表不完整，请稍后重试");
            }
            total = response.getTotalCount();
            pageCount = response.getListPageCount();
            for (var value : values) {
                Long id = value == null ? null : value.getMemberId();
                if (id == null || id <= 0 || !seen.add(id)) {
                    throw new BusinessException("电子签企业成员列表异常，请稍后重试");
                }
                members.add(value);
            }
            if (page >= pageCount) {
                if (members.size() != total) throw new BusinessException("电子签企业成员列表不完整，请稍后重试");
                return List.copyOf(members);
            }
        }
        throw new BusinessException("电子签企业成员分页数量异常，请联系管理员核验");
    }

    @Override
    public CompanyMember findMemberByInternalIdentifier(String openCorpId, String internalIdentifier) {
        if (!hasText(internalIdentifier)) throw new BusinessException("企业成员关联标识缺失");
        // get-detail supports internalIdentifier, but its public error table does not
        // define a member-not-found code. A complete list distinguishes absence from
        // provider/permission failures without turning an unknown error into a create.
        var matches = listMemberCandidates(openCorpId, null).stream()
                .filter(value -> internalIdentifier.equals(value.getInternalIdentifier())).toList();
        if (matches.isEmpty()) return null;
        if (matches.size() != 1) throw new BusinessException("电子签企业成员关联重复，请联系管理员核验");
        var member = getMember(openCorpId, matches.get(0).getMemberId());
        if (!matches.get(0).getMemberId().equals(member.memberId()) || !internalIdentifier.equals(member.internalIdentifier())) {
            throw new BusinessException("电子签企业成员关联信息不一致，请联系管理员核验");
        }
        return member;
    }

    @Override
    public MemberInvitation createMember(MemberCreateCommand command) {
        if (!hasText(command.openCorpId()) || !hasText(command.internalIdentifier())
                || !hasText(command.memberName()) || !hasText(command.accountName())) {
            throw new BusinessException("企业成员同步资料不完整");
        }
        CreateCorpMemberReq request = new CreateCorpMemberReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(command.openCorpId());
        request.setNotifyActiveByEmail(false);
        EmployeeInfo employee = new EmployeeInfo();
        employee.setMemberName(command.memberName());
        employee.setInternalIdentifier(command.internalIdentifier());
        if (command.accountName().contains("@")) employee.setMemberEmail(command.accountName());
        else employee.setMemberMobile(command.accountName());
        request.setEmployeeInfos(List.of(employee));
        // Do not pass roles or the undocumented mergeMemberInfo extension. Existing
        // member information and remote administrative permissions remain authoritative.
        var response = invoke(request, () -> orgClient.createCorpMember(request), "同步电子签企业成员");
        if (response.size() != 1 || response.get(0) == null
                || response.get(0).getMemberId() == null || response.get(0).getMemberId() <= 0
                || !command.internalIdentifier().equals(response.get(0).getInternalIdentifier())) {
            throw new BusinessException("电子签成员同步结果待确认，请稍后重新同步");
        }
        var member = response.get(0);
        return new MemberInvitation(member.getMemberId(), member.getInternalIdentifier(),
                member.getMemberStatus(), member.getMemberActiveEmbedUrl());
    }

    @Override
    public String createMemberActivationUrl(String openCorpId, long memberId) {
        if (!hasText(openCorpId) || memberId <= 0) throw new BusinessException("企业成员标识缺失");
        GetCorpMemberActiveUrlReq request = new GetCorpMemberActiveUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(openCorpId);
        request.setMemberIds(List.of(memberId));
        var response = invoke(request, () -> orgClient.getCorpMemberActiveUrl(request), "获取本人加入企业地址");
        if (response.size() != 1 || response.get(0) == null || !Long.valueOf(memberId).equals(response.get(0).getMemberId())
                || !hasText(response.get(0).getMemberActiveEmbedUrl())
                || !response.get(0).getMemberActiveEmbedUrl().startsWith("https://")) {
            throw new BusinessException("未获取到本人加入企业地址，请稍后重新同步");
        }
        return response.get(0).getMemberActiveEmbedUrl();
    }

    @Override
    public String createOrganizationManageUrl(String openCorpId, String clientUserId) {
        GetCorpOrgManageUrlReq request = new GetCorpOrgManageUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(openCorpId);
        request.setClientUserId(clientUserId);
        request.setModules(List.of("member"));
        // This official editor is PC-only and has no redirectMiniAppUrl parameter.
        var response = invoke(request, () -> orgClient.getCorpOrgManageUrl(request), "获取企业成员管理地址");
        if (!hasText(response.getResourceUrl()) || !response.getResourceUrl().startsWith("https://")) {
            throw new BusinessException("未获取到企业成员管理地址，请稍后重试");
        }
        return response.getResourceUrl();
    }

    @Override
    public List<SealInfo> listSeals(String openCorpId) {
        var result = new java.util.ArrayList<SealInfo>();
        var seen = new java.util.HashSet<String>();
        for (int page = 1; page <= 1000; page++) {
            GetSealInfoListReq request = new GetSealInfoListReq();
            request.setAccessToken(tokenProvider.get());
            request.setOpenCorpId(openCorpId);
            request.setListPageNo(page);
            request.setListPageSize(100);
            GetSealInfoListRes response = invoke(request, () -> sealClient.getSealInfoListNew(request), "查询电子印章");
            var seals = response.getSealInfos();
            if (response.getListPageNo() == null || response.getListPageNo() != page
                    || response.getListPageCount() == null || response.getListPageCount() < 0
                    || seals == null || (page < response.getListPageCount() && seals.isEmpty())) {
                throw new BusinessException("电子印章分页结果不完整，请稍后重试");
            }
            for (var value : seals) {
                // A subsidiary's seal must never be selected for the primary company.
                if (!"primary".equals(value.getEntityType())) continue;
                String id = value.getSealId() == null ? null : String.valueOf(value.getSealId());
                if (id != null && !seen.add(id)) throw new BusinessException("电子印章分页结果重复，请稍后重试");
                result.add(new SealInfo(id, value.getSealName(), value.getCategoryType(), value.getSealStatus()));
            }
            if (page >= response.getListPageCount()) return List.copyOf(result);
        }
        throw new BusinessException("电子印章分页数量异常，请联系管理员核验");
    }

    @Override
    public String createIdentityChangeUrl(String clientCorpId, String openCorpId, String clientUserId) {
        if (!hasText(openCorpId)) throw new BusinessException("企业工商信息变更需要已确认的企业标识");
        GetChangeCorpIdentityInfoUrlReq request = new GetChangeCorpIdentityInfoUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(openCorpId);
        request.setClientUserId(clientUserId);
        var response = invoke(request, () -> corpClient.getChangeCorpIdentityInfoUrl(request), "获取企业工商信息变更地址");
        if (!hasText(response.getChangeIdentityInfoUrl())) throw new BusinessException("未获取到企业工商信息变更地址，请稍后重试");
        return response.getChangeIdentityInfoUrl();
    }

    @Override
    public String createSealManageUrl(String openCorpId, String clientUserId, String redirectUrl) {
        GetSealManageUrlReq request = new GetSealManageUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(openCorpId);
        request.setClientUserId(clientUserId);
        if (hasText(redirectUrl)) request.setRedirectUrl(redirectUrl);
        GetSealManageUrlRes response = invoke(request, () -> sealClient.getSealManageUrl(request), "获取印章管理地址");
        if (!hasText(response.getResourceUrl())) throw new BusinessException("未获取到印章管理地址，请稍后重试");
        return response.getResourceUrl();
    }

    static void applyCompanyLookup(GetCorpReq request, String clientCorpId, String openCorpId) {
        if (hasText(openCorpId)) request.setOpenCorpId(openCorpId);
        else if (hasText(clientCorpId)) request.setClientCorpId(clientCorpId);
        else throw new BusinessException("企业认证标识缺失");
    }

    private <T> T invoke(BaseReq request, FadadaApiSupport.ApiCall<T> call, String action) {
        try {
            BaseRes<T> response = FadadaApiSupport.execute(request, tokenProvider, call);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                log.warn("Electronic signature {} failed: code={}, requestId={}, message={}", action,
                        response == null ? "null" : response.getCode(),
                        response == null ? "" : response.getRequestId(),
                        SdkFadadaUserGateway.safeDiagnosticMessage(response == null ? null : response.getMsg()));
                String code = response == null ? "" : String.valueOf(response.getCode());
                if (List.of("100004", "100009", "100020").contains(code)
                        || ("查询企业认证状态".equals(action) && "210032".equals(code))
                        || ("获取企业认证地址".equals(action) && "210002".equals(code))) {
                    throw new FadadaCompanyQueryException(code);
                }
                throw new BusinessException(action + "失败，请稍后重试");
            }
            return response.getData();
        } catch (ApiException exception) {
            log.warn("Electronic signature {} failed", action, exception);
            throw new BusinessException(action + "失败，请稍后重试");
        }
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

}
