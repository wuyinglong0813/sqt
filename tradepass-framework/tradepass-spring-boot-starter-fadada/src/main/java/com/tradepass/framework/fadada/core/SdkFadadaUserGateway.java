package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.bean.common.UserIdentInfo;
import com.fasc.open.api.exception.ApiException;
import com.fasc.open.api.v5_1.client.OpenApiClient;
import com.fasc.open.api.v5_1.client.UserClient;
import com.fasc.open.api.v5_1.req.user.GetUserAuthUrlReq;
import com.fasc.open.api.v5_1.req.user.GetUserIdentityInfoReq;
import com.fasc.open.api.v5_1.req.user.GetUserReq;
import com.fasc.open.api.v5_1.res.common.EUrlRes;
import com.fasc.open.api.v5_1.res.user.UserIdentityInfoRes;
import com.fasc.open.api.v5_1.res.user.UserRes;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SdkFadadaUserGateway implements FadadaUserGateway {
    private static final Logger log = LoggerFactory.getLogger(SdkFadadaUserGateway.class);
    private final UserClient userClient;
    private final FadadaAccessTokenProvider tokenProvider;

    public SdkFadadaUserGateway(FadadaProperties properties, FadadaAccessTokenProvider tokenProvider) {
        OpenApiClient openApiClient = new OpenApiClient(
                properties.getAppId(), properties.getAppSecret(), properties.getServerUrl());
        this.userClient = new UserClient(openApiClient);
        this.tokenProvider = tokenProvider;
    }

    @Override
    public AuthUrlResult createAuthUrl(AuthUrlCommand command) {
        GetUserAuthUrlReq request = new GetUserAuthUrlReq();
        request.setAccessToken(tokenProvider.get());
        request.setClientUserId(command.clientUserId());
        request.setAuthScopes(List.of("ident_info"));
        if (hasText(command.accountName())) {
            // Prefill the current phone number while allowing edits on the authentication page.
            request.setAccountName(command.accountName());
        }
        if (hasText(command.callbackUrl())) request.setCallbackUrl(command.callbackUrl());
        if (hasText(command.redirectUrl())) request.setRedirectUrl(command.redirectUrl());
        if (hasText(command.redirectMiniAppUrl())) request.setRedirectMiniAppUrl(command.redirectMiniAppUrl());
        EUrlRes response = invoke(() -> userClient.getUserAuthUrl(request), "获取个人认证地址");
        String authUrl = firstText(response.getAuthUrl(), response.geteUrl(), response.getAuthShortUrl());
        if (!hasText(authUrl)) throw new BusinessException("认证服务未返回个人认证地址");
        return new AuthUrlResult(authUrl);
    }

    @Override
    public UserAccountResult getUser(String clientUserId, String openUserId) {
        GetUserReq request = new GetUserReq();
        request.setAccessToken(tokenProvider.get());
        applyUserLookup(request, clientUserId, openUserId);
        UserRes response = invoke(() -> userClient.get(request), "查询个人认证状态");
        return new UserAccountResult(response.getClientUserId(), response.getOpenUserId(),
                response.getBindingStatus(), response.getIdentStatus(), response.getAuthScope());
    }

    @Override
    public UserIdentityResult getIdentityInfo(String openUserId) {
        GetUserIdentityInfoReq request = new GetUserIdentityInfoReq();
        request.setAccessToken(tokenProvider.get());
        request.setOpenUserId(openUserId);
        UserIdentityInfoRes response = invoke(() -> userClient.getIdentityInfo(request), "查询个人实名信息");
        UserIdentInfo identity = response.getUserIdentInfo();
        return new UserIdentityResult(response.getOpenUserId(), response.getIdentStatus(),
                identity == null ? null : identity.getUserName(), response.getIdentMethod(),
                response.getIdentSubmitTime(), response.getIdentSuccessTime());
    }

    static void applyUserLookup(GetUserReq request, String clientUserId, String openUserId) {
        if (hasText(openUserId)) {
            request.setOpenUserId(openUserId);
            return;
        }
        if (hasText(clientUserId)) {
            request.setClientUserId(clientUserId);
            return;
        }
        throw new BusinessException("个人认证用户标识缺失");
    }

    private <T> T invoke(ApiCall<T> call, String action) {
        try {
            BaseRes<T> response = call.call();
            if (response == null || !response.isSuccess() || response.getData() == null) {
                String requestId = response == null ? "" : response.getRequestId();
                String code = response == null ? "null" : response.getCode();
                log.warn("Fadada {} failed: code={}, requestId={}, message={}", action, code, requestId,
                        safeDiagnosticMessage(response == null ? null : response.getMsg()));
                if (("查询个人认证状态".equals(action)
                        && ("210022".equals(code) || "100020".equals(code)))
                        || ("获取个人认证地址".equals(action) && "210002".equals(code))) {
                    throw new FadadaUserQueryException(code);
                }
                throw new BusinessException(action + "失败，请稍后重试");
            }
            return response.getData();
        } catch (ApiException exception) {
            log.warn("Fadada {} request failed", action, exception);
            throw new BusinessException(action + "失败，请稍后重试");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // Retain provider diagnostics on the server without exposing identifiers or URLs.
    static String safeDiagnosticMessage(String message) {
        if (message == null) return "";
        String safe = message.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("https?://\\S+", "[url]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "[email]")
                .replaceAll("[A-Za-z0-9_-]*[0-9][A-Za-z0-9_-]{10,}", "[identifier]");
        return safe.substring(0, Math.min(safe.length(), 300));
    }

    private String firstText(String... values) {
        for (String value : values) if (hasText(value)) return value;
        return null;
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        BaseRes<T> call() throws ApiException;
    }
}
