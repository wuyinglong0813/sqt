package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.bean.base.BaseReq;
import com.fasc.open.api.bean.common.CorpIdentInfo;
import com.fasc.open.api.exception.ApiException;
import com.fasc.open.api.v5_1.client.CorpClient;
import com.fasc.open.api.v5_1.client.OpenApiClient;
import com.fasc.open.api.v5_1.client.OrgClient;
import com.fasc.open.api.v5_1.req.org.GetCorpMemberDetailReq;
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
        if (hasText(primary.operatorId()) && hasText(primary.operatorType())) return primary;
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
        return new CompanyMember(response.getMemberId(), response.getMemberStatus(),
                response.getRoleType(), response.getFddId());
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
