package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.bean.common.CorpIdentInfo;
import com.fasc.open.api.exception.ApiException;
import com.fasc.open.api.v5_1.client.CorpClient;
import com.fasc.open.api.v5_1.client.OpenApiClient;
import com.fasc.open.api.v5_1.client.SealClient;
import com.fasc.open.api.v5_1.req.corp.GetCorpAuthResourceUrlReq;
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

import java.util.List;

@Component
public class SdkFadadaCompanyGateway implements FadadaCompanyGateway {
    private static final Logger log = LoggerFactory.getLogger(SdkFadadaCompanyGateway.class);
    private final CorpClient corpClient;
    private final SealClient sealClient;
    private final FadadaAccessTokenProvider tokenProvider;

    public SdkFadadaCompanyGateway(FadadaProperties properties, FadadaAccessTokenProvider tokenProvider) {
        OpenApiClient client = new OpenApiClient(
                properties.getAppId(), properties.getAppSecret(), properties.getServerUrl());
        this.corpClient = new CorpClient(client);
        this.sealClient = new SealClient(client);
        this.tokenProvider = tokenProvider;
    }

    @Override
    public String createAuthUrl(AuthCommand command) {
        GetCorpAuthResourceUrlReq request = new GetCorpAuthResourceUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setClientCorpId(command.clientCorpId());
        request.setClientUserId(command.clientUserId());
        request.setCorpName(command.companyName());
        request.setCorpIdentNo(command.creditCode());
        request.setCorpIdentInfoMatch(true);
        request.setCorpNonEditableInfo(List.of("corpName", "corpIdentNo"));
        request.setAuthScopes(command.authScopes());
        request.setCallbackUrl(command.callbackUrl());
        if (hasText(command.redirectMiniAppUrl())) request.setRedirectMiniAppUrl(command.redirectMiniAppUrl());
        ECorpAuthUrlRes response = invoke(() -> corpClient.getCorpAuthUrl(request), "获取企业认证地址");
        if (!hasText(response.getAuthUrl())) throw new BusinessException("未获取到企业认证地址，请稍后重试");
        return response.getAuthUrl();
    }

    @Override
    public CompanyAccount getCompany(String clientCorpId, String openCorpId) {
        GetCorpReq request = new GetCorpReq();
        request.setAccessToken(tokenProvider.get());
        applyCompanyLookup(request, clientCorpId, openCorpId);
        CorpRes response = invoke(() -> corpClient.get(request), "查询企业认证状态");
        return new CompanyAccount(response.getClientCorpId(), response.getOpenCorpId(),
                response.getBindingStatus(), response.getIdentStatus(), response.getAvailableStatus(),
                response.getAuthScope());
    }

    @Override
    public CompanyIdentity getIdentity(String openCorpId) {
        GetCorpIdentityInfoReq request = new GetCorpIdentityInfoReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(openCorpId);
        CorpIdentityInfoRes response = invoke(() -> corpClient.getIdentityInfo(request), "查询企业认证信息");
        return toCompanyIdentity(response);
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
    public List<SealInfo> listSeals(String openCorpId) {
        GetSealInfoListReq request = new GetSealInfoListReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(openCorpId);
        request.setListPageNo(1);
        request.setListPageSize(100);
        GetSealInfoListRes response = invoke(() -> sealClient.getSealInfoListNew(request), "查询电子印章");
        if (response.getSealInfos() == null) return List.of();
        return response.getSealInfos().stream().map(value -> new SealInfo(
                value.getSealId() == null ? null : String.valueOf(value.getSealId()),
                value.getSealName(), value.getCategoryType(), value.getSealStatus())).toList();
    }

    @Override
    public String createIdentityChangeUrl(String clientCorpId, String openCorpId, String clientUserId) {
        GetChangeCorpIdentityInfoUrlReq request = new GetChangeCorpIdentityInfoUrlReq();
        request.setAccessToken(tokenProvider.get());
        if (hasText(openCorpId)) request.setOpenCorpId(openCorpId);
        else request.setClientCorpId(clientCorpId);
        request.setClientUserId(clientUserId);
        var response = invoke(() -> corpClient.getChangeCorpIdentityInfoUrl(request), "获取企业法人核验地址");
        if (!hasText(response.getChangeIdentityInfoUrl())) throw new BusinessException("未获取到企业法人核验地址，请稍后重试");
        return response.getChangeIdentityInfoUrl();
    }

    @Override
    public String createSealManageUrl(String openCorpId, String clientUserId, String redirectUrl) {
        GetSealManageUrlReq request = new GetSealManageUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenCorpId(openCorpId);
        request.setClientUserId(clientUserId);
        if (hasText(redirectUrl)) request.setRedirectUrl(redirectUrl);
        GetSealManageUrlRes response = invoke(() -> sealClient.getSealManageUrl(request), "获取印章管理地址");
        if (!hasText(response.getResourceUrl())) throw new BusinessException("未获取到印章管理地址，请稍后重试");
        return response.getResourceUrl();
    }

    static void applyCompanyLookup(GetCorpReq request, String clientCorpId, String openCorpId) {
        if (hasText(openCorpId)) request.setOpenCorpId(openCorpId);
        else if (hasText(clientCorpId)) request.setClientCorpId(clientCorpId);
        else throw new BusinessException("企业认证标识缺失");
    }

    private <T> T invoke(ApiCall<T> call, String action) {
        try {
            BaseRes<T> response = call.call();
            if (response == null || !response.isSuccess() || response.getData() == null) {
                log.warn("Electronic signature {} failed: code={}, requestId={}, message={}", action,
                        response == null ? "null" : response.getCode(),
                        response == null ? "" : response.getRequestId(),
                        SdkFadadaUserGateway.safeDiagnosticMessage(response == null ? null : response.getMsg()));
                throw new BusinessException(action + "失败，请稍后重试");
            }
            return response.getData();
        } catch (ApiException exception) {
            log.warn("Electronic signature {} failed", action, exception);
            throw new BusinessException(action + "失败，请稍后重试");
        }
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    @FunctionalInterface
    private interface ApiCall<T> { BaseRes<T> call() throws ApiException; }
}
