package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.fadada.core.FadadaUserQueryException;
import com.tradepass.framework.fadada.core.FadadaAuthorizationCallback;
import com.tradepass.module.identity.api.fadada.FadadaPersonalIdentityOperations;
import com.tradepass.module.identity.api.fadada.FadadaPersonalIdentityOperations.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import static com.tradepass.framework.common.exception.SigningErrorCodes.*;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.api.fadada.dto.FadadaAuthUrlRespDTO;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaUserIdentityDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.framework.fadada.core.FadadaUserGateway;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class FadadaPersonalIdentityServiceImpl implements FadadaPersonalIdentityService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FadadaPersonalIdentityServiceImpl.class);
    private static final String AUTH_SCOPE = "ident_info";
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final FadadaUserIdentityMapper identityMapper;
    private final SysUserMapper userMapper;
    private final FadadaUserGateway gateway;
    private final FadadaProperties properties;
    private final ObjectMapper objectMapper;
    private ApplicationEventPublisher events;

    @Autowired
    public void setEventPublisher(ApplicationEventPublisher events) { this.events = events; }

    public FadadaPersonalIdentityServiceImpl(FadadaUserIdentityMapper identityMapper,
                                         SysUserMapper userMapper,
                                         FadadaUserGateway gateway,
                                         FadadaProperties properties,
                                         ObjectMapper objectMapper) {
        this.identityMapper = identityMapper;
        this.userMapper = userMapper;
        this.gateway = gateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public PersonalIdentityRespDTO current() {
        return toPayload(findByUserId(AuthContext.userId()));
    }

    @Transactional
    public PersonalIdentityRespDTO syncCurrent() {
        requireReady();
        FadadaUserIdentityDO identity = findByUserIdForUpdate(AuthContext.userId());
        if (identity == null) return toPayload(null);
        if (identity.getLastSyncAt() != null
                && identity.getLastSyncAt().isAfter(LocalDateTime.now().minusSeconds(30))) {
            return toPayload(identity);
        }
        try {
            return toPayload(sync(identity));
        } catch (com.tradepass.framework.fadada.core.FadadaUserQueryException exception) {
            // Missing authorization and throttling are not successful verification.
            // Persist the retry interval so concurrent clients cannot hammer the provider.
            identity.setLastSyncAt(LocalDateTime.now());
            identity.setFailureReason(exception.getMessage());
            identityMapper.updateById(identity);
            return toPayload(identity);
        }
    }

    @Transactional
    public PersonalIdentityRespDTO requireCurrentVerified() {
        requireReady();
        FadadaUserIdentityDO identity = findByUserIdForUpdate(AuthContext.userId());
        if (identity != null && (!"VERIFIED".equals(identity.getLocalStatus()) || identity.getLastSyncAt() == null
                || identity.getLastSyncAt().isBefore(LocalDateTime.now().minusSeconds(30)))) {
            identity = sync(identity);
        }
        if (identity == null || !"VERIFIED".equals(identity.getLocalStatus())) {
            throw new BusinessException("请先完成个人实名认证，再创建或认证企业");
        }
        return toPayload(identity);
    }

    /** Provider identifiers, never names, bind a certification operator to a local applicant. */
    public String verifiedOpenUserId(long userId) {
        FadadaUserIdentityDO identity = findByUserId(userId);
        if (identity == null || !"VERIFIED".equals(identity.getLocalStatus())
                || identity.getOpenUserId() == null || identity.getOpenUserId().isBlank()) {
            throw new BusinessException(PERSONAL_IDENTITY_REQUIRED, "该账号的个人实名身份尚未确认，请先完成个人认证后继续");
        }
        return identity.getOpenUserId();
    }

    /** Compare the provider person to the enterprise member without persisting contact data. */
    public String verifiedFddId(long userId) {
        String fddId = verifiedPerson(userId).fddId();
        if (fddId == null || fddId.isBlank()) {
            throw new BusinessException("认证服务尚未返回可核对的个人标识，请联系管理员核验");
        }
        return fddId;
    }

    public VerifiedPerson verifiedPerson(long userId) {
        String openUserId = verifiedOpenUserId(userId);
        String clientUserId = "tradepass-user-" + userId;
        var account = gateway.getUser(clientUserId, null);
        if (account == null || !clientUserId.equals(account.clientUserId())
                || !openUserId.equals(account.openUserId())
                || !"enable".equals(account.availableStatus()) || !"authorized".equals(account.bindingStatus())
                || !"identified".equals(account.identStatus())
                || account.authScopes() == null || !account.authScopes().contains(AUTH_SCOPE)) {
            throw new BusinessException(PERSONAL_IDENTITY_REQUIRED, "个人账号授权关联尚未确认，请重新同步个人认证");
        }
        var detail = gateway.getIdentityInfo(openUserId);
        if (detail == null || !openUserId.equals(detail.openUserId()) || !"identified".equals(detail.identStatus())) {
            throw new BusinessException("认证服务尚未返回可核对的个人标识，请联系管理员核验");
        }
        return new VerifiedPerson(openUserId, detail.fddId(), detail.userName(), detail.accountName());
    }

    @Transactional
    public FadadaAuthUrlRespDTO createAuthUrl() {
        requireReady();
        long userId = AuthContext.userId();
        SysUserDO user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("当前用户不存在");
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new BusinessException("请先绑定手机号，再进行个人认证");
        }
        FadadaUserIdentityDO identity = ensureIdentity(userId);
        if ("VERIFIED".equals(identity.getLocalStatus())) {
            identity = sync(identity);
            if ("VERIFIED".equals(identity.getLocalStatus())) return new FadadaAuthUrlRespDTO(null, toPayload(identity));
        }
        // redirectMiniAppUrl accepts an encoded native, non-tabBar page (FASC 5.1).
        // The return page confirms server state and restores the originating company flow.
        FadadaUserGateway.AuthUrlResult result;
        LocalDateTime attemptStartedAt = LocalDateTime.now();
        try {
            result = gateway.createAuthUrl(new FadadaUserGateway.AuthUrlCommand(
                identity.getClientUserId(), user.getPhone(), callbackUrl(),
                null, java.net.URLEncoder.encode("/pages/service-return/service-return?scene=personal",
                        java.nio.charset.StandardCharsets.UTF_8)));
        } catch (com.tradepass.framework.fadada.core.FadadaUserQueryException exception) {
            if (!"210002".equals(exception.providerCode())) throw exception;
            // Already authorized is a reconciliation cue, not proof of verified identity.
            // Respect the existing query cooldown; the result page waits for fresh evidence.
            return new FadadaAuthUrlRespDTO(null, syncCurrent());
        }
        validateAuthUrl(result.authUrl());
        identity.setLocalStatus("IN_PROGRESS");
        identity.setIdentProcessStatus("identifying");
        identity.setFailureReason("");
        identity.setIdentSubmittedAt(attemptStartedAt);
        identityMapper.updateById(identity);
        return new FadadaAuthUrlRespDTO(result.authUrl(), toPayload(identity));
    }

    @Transactional
    public PersonalIdentityRespDTO syncByClientUserId(String clientUserId) {
        FadadaUserIdentityDO identity = findByClientUserId(clientUserId);
        return identity == null ? null : toPayload(sync(identity));
    }

    @Transactional
    public PersonalIdentityRespDTO syncCallback(String clientUserId, JsonNode data) {
        FadadaUserIdentityDO identity = findByClientUserId(clientUserId);
        if (identity == null) return null;
        String openUserId = callbackText(data, "openUserId", "existOpenUserId");
        String process = callbackText(data, "identProcessStatus", "verifyStatus");
        String method = callbackText(data, "identMethod");
        String authResult = callbackText(data, "authResult");
        String failureReason = callbackText(data, "identFailedReason", "authFailedReason");
        if (hasText(identity.getOpenUserId()) && hasText(openUserId)
                && !identity.getOpenUserId().equals(openUserId)) {
            throw new BusinessException("认证回调用户标识与当前账号不一致");
        }
        LocalDateTime eventTime = FadadaAuthorizationCallback.time(data);
        String event = callbackText(data, "_verifiedEvent");
        boolean authorizationEvent = "user-authorize".equals(event) || "user-cancel-authorization".equals(event);
        boolean tiedWithRevocation = eventTime != null && eventTime.equals(identity.getAuthorizationEventAt())
                && "user-cancel-authorization".equals(identity.getAuthorizationEventType());
        if (authorizationEvent && eventTime != null) {
            if (identity.getAuthorizationEventAt() != null && eventTime.isBefore(identity.getAuthorizationEventAt())) {
                return toPayload(identity).withCallbackObsolete(true);
            }
            if (!tiedWithRevocation || "user-cancel-authorization".equals(event)) {
                identity.setAuthorizationEventAt(eventTime);
                identity.setAuthorizationEventType(event);
            }
        }
        if ("user-cancel-authorization".equals(event) && eventTime != null) {
            if (tiedWithRevocation && "VERIFIED".equals(identity.getLocalStatus())) return toPayload(identity);
            if (!tiedWithRevocation) identity.setAuthorizationRevokedAt(LocalDateTime.now());
            markRevoked(identity);
            identityMapper.updateById(identity);
            return toPayload(identity);
        }
        if (tiedWithRevocation && "user-authorize".equals(event)) {
            return toPayload(identity).withCallbackObsolete(true);
        }
        // Repeated success must not query again. Other late events use current provider
        // evidence: processing time is not the event order (a newer revocation may arrive late).
        if ("VERIFIED".equals(identity.getLocalStatus()) && "success".equals(authResult)
                && eventTime != null && identity.getIdentVerifiedAt() != null
                && !eventTime.isAfter(identity.getIdentVerifiedAt())) {
            identityMapper.updateById(identity);
            return toPayload(identity);
        }
        boolean firstCompletion = "IN_PROGRESS".equals(identity.getLocalStatus())
                && identity.getIdentVerifiedAt() == null;
        if (FadadaAuthorizationCallback.completed(data, "user-authorize", "identProcessStatus", List.of(AUTH_SCOPE))
                && clientUserId.equals(callbackText(data, "clientUserId"))
                && identity.getIdentSubmittedAt() != null && !eventTime.isBefore(identity.getIdentSubmittedAt().withNano(0))
                && (firstCompletion || identity.getLastSyncAt() == null || eventTime.isAfter(identity.getLastSyncAt()))
                && hasText(openUserId) && hasText(callbackText(data, "openUserId"))) {
            identity.setOpenUserId(openUserId);
            identity.setBindingStatus("authorized");
            identity.setIdentStatus("identified");
            identity.setIdentProcessStatus("success");
            identity.setAuthScopes(json(FadadaAuthorizationCallback.scopes(data)));
            String name = callbackText(data, "userName");
            if (hasText(name)) identity.setVerifiedName(name);
            if (hasText(method)) identity.setIdentMethod(method);
            identity.setIdentVerifiedAt(eventTime);
            identity.setLocalStatus("VERIFIED");
            identity.setFailureReason("");
            identity.setLastSyncAt(beijingNow());
            identityMapper.updateById(identity);
            requestSigningPreparation(identity.getUserId());
            return toPayload(identity);
        }
        if (hasText(openUserId)) identity.setOpenUserId(openUserId);
        if (hasText(process)) identity.setIdentProcessStatus(process);
        if (hasText(method)) identity.setIdentMethod(method);
        if ("fail".equalsIgnoreCase(authResult) || "failed".equalsIgnoreCase(authResult)
                || "failed".equalsIgnoreCase(process)) {
            if ("VERIFIED".equals(identity.getLocalStatus()) || "exist".equals(failureReason)) {
                return toPayload(sync(identity));
            }
            identity.setLocalStatus("FAILED");
            identity.setFailureReason(hasText(failureReason) ? failureReason : "个人认证未通过");
            identity.setLastSyncAt(LocalDateTime.now());
            identityMapper.updateById(identity);
            return toPayload(identity);
        }
        identityMapper.updateById(identity);
        return toPayload(sync(identity));
    }

    private FadadaUserIdentityDO sync(FadadaUserIdentityDO identity) {
        // A signed revocation wins over briefly stale provider reads. The scheduled current
        // query can confirm later consent even when its authorization callback was missed.
        if ("user-cancel-authorization".equals(identity.getAuthorizationEventType())
                && identity.getAuthorizationRevokedAt() != null
                && identity.getAuthorizationRevokedAt().isAfter(LocalDateTime.now().minusSeconds(30))) {
            markRevoked(identity);
            identityMapper.updateById(identity);
            return identity;
        }
        FadadaUserGateway.UserAccountResult account;
        try {
            account = gateway.getUser(identity.getClientUserId(), identity.getOpenUserId());
        } catch (FadadaUserQueryException exception) {
            if (!"210022".equals(exception.providerCode())) throw exception;
            identity.setLocalStatus("IN_PROGRESS");
            identity.setBindingStatus("unauthorized");
            identity.setAuthScopes("[]");
            identity.setFailureReason(exception.getMessage());
            identity.setLastSyncAt(LocalDateTime.now());
            identityMapper.updateById(identity);
            return identity;
        }
        if (java.util.List.of(
                new com.tradepass.framework.fadada.core.FadadaUserQueryException("210022").getMessage(),
                new com.tradepass.framework.fadada.core.FadadaUserQueryException("100020").getMessage())
                .contains(identity.getFailureReason() == null ? "" : identity.getFailureReason())) {
            identity.setFailureReason("");
        }
        if (!identity.getClientUserId().equals(account.clientUserId())) {
            throw new BusinessException("认证服务用户绑定与当前账号不一致");
        }
        if (hasText(account.openUserId())) identity.setOpenUserId(account.openUserId());
        if (hasText(account.bindingStatus())) identity.setBindingStatus(account.bindingStatus());
        if (hasText(account.identStatus())) identity.setIdentStatus(account.identStatus());
        if (account.authScopes() != null) identity.setAuthScopes(json(account.authScopes()));

        boolean accountReady = "enable".equalsIgnoreCase(account.availableStatus())
                && "authorized".equalsIgnoreCase(account.bindingStatus())
                && account.authScopes() != null && account.authScopes().contains(AUTH_SCOPE)
                && "identified".equalsIgnoreCase(account.identStatus()) && hasText(account.openUserId());
        boolean confirmed = false;
        if (accountReady) {
            FadadaUserGateway.UserIdentityResult detail = gateway.getIdentityInfo(identity.getOpenUserId());
            if (!identity.getOpenUserId().equals(detail.openUserId())) {
                identity.setLocalStatus("IN_PROGRESS");
                identity.setFailureReason("个人实名信息与当前认证账号不一致，请联系管理员核验账号关联");
                identity.setLastSyncAt(LocalDateTime.now());
                identityMapper.updateById(identity);
                return identity;
            }
            confirmed = "identified".equalsIgnoreCase(detail.identStatus());
            if (hasText(detail.identStatus())) identity.setIdentStatus(detail.identStatus());
            if (confirmed) {
                if (hasText(detail.userName())) identity.setVerifiedName(detail.userName());
                if (hasText(detail.identMethod())) identity.setIdentMethod(detail.identMethod());
                identity.setIdentSubmittedAt(parseTime(detail.identSubmitTime(), identity.getIdentSubmittedAt()));
                identity.setIdentVerifiedAt(parseTime(detail.identSuccessTime(), beijingNow()));
            }
        }
        identity.setLocalStatus(resolveLocalStatus(identity, confirmed));
        if (!"enable".equalsIgnoreCase(account.availableStatus())) {
            identity.setFailureReason("个人电子签账号未启用，请核对账号状态后重新同步");
        } else if (account.authScopes() == null || !account.authScopes().contains(AUTH_SCOPE)) {
            identity.setFailureReason("个人身份信息授权尚未完成，请重新办理授权后同步");
        }
        if ("VERIFIED".equals(identity.getLocalStatus())) {
            identity.setIdentProcessStatus("success");
            identity.setFailureReason("");
        }
        identity.setLastSyncAt(LocalDateTime.now());
        identityMapper.updateById(identity);
        if ("VERIFIED".equals(identity.getLocalStatus())) requestSigningPreparation(identity.getUserId());
        return identity;
    }

    private void requestSigningPreparation(Long targetUserId) {
        if (events == null || targetUserId == null) return;
        try {
            Long actor = AuthContext.isLoggedIn() ? AuthContext.userId() : null;
            events.publishEvent(new SigningMemberSyncRequested(null, targetUserId, actor, "PERSONAL_IDENTITY_VERIFIED"));
        } catch (RuntimeException failure) {
            log.warn("Signing member preparation event deferred: targetUserId={}", targetUserId);
        }
    }

    private void markRevoked(FadadaUserIdentityDO identity) {
        identity.setBindingStatus("unauthorized");
        identity.setAuthScopes("[]");
        identity.setLocalStatus("IN_PROGRESS");
        identity.setFailureReason("个人授权已解除，请重新办理授权；状态将在后台继续核对");
        identity.setLastSyncAt(LocalDateTime.now());
    }

    private FadadaUserIdentityDO ensureIdentity(long userId) {
        FadadaUserIdentityDO existing = findByUserIdForUpdate(userId);
        if (existing != null) return existing;
        FadadaUserIdentityDO identity = new FadadaUserIdentityDO();
        identity.setUserId(userId);
        identity.setClientUserId("tradepass-user-" + userId);
        identity.setLocalStatus("NOT_STARTED");
        identity.setBindingStatus("unauthorized");
        identity.setIdentStatus("unidentified");
        identity.setIdentProcessStatus("no_start");
        identity.setAuthScopes(json(List.of(AUTH_SCOPE)));
        identityMapper.insert(identity);
        return identity;
    }

    private FadadaUserIdentityDO findByUserId(long userId) {
        return identityMapper.selectOne(new LambdaQueryWrapper<FadadaUserIdentityDO>()
                .eq(FadadaUserIdentityDO::getUserId, userId).last("LIMIT 1"));
    }

    private FadadaUserIdentityDO findByUserIdForUpdate(long userId) {
        return identityMapper.selectOne(new LambdaQueryWrapper<FadadaUserIdentityDO>()
                .eq(FadadaUserIdentityDO::getUserId, userId).last("LIMIT 1 FOR UPDATE"));
    }

    private FadadaUserIdentityDO findByClientUserId(String clientUserId) {
        return identityMapper.selectOne(new LambdaQueryWrapper<FadadaUserIdentityDO>()
                .eq(FadadaUserIdentityDO::getClientUserId, clientUserId).last("LIMIT 1 FOR UPDATE"));
    }

    private PersonalIdentityRespDTO toPayload(FadadaUserIdentityDO identity) {
        if (identity == null) {
            return new PersonalIdentityRespDTO(properties.isEnabled(), "NOT_STARTED", "待认证",
                    "unauthorized", "unidentified", "no_start", null, null,
                    null, null, null, null);
        }
        String status = hasText(identity.getLocalStatus()) ? identity.getLocalStatus() : "NOT_STARTED";
        return new PersonalIdentityRespDTO(properties.isEnabled(), status, statusText(status),
                identity.getBindingStatus(), identity.getIdentStatus(), identity.getIdentProcessStatus(),
                identity.getVerifiedName(), identity.getIdentMethod(),
                hasText(identity.getFailureReason()) ? identity.getFailureReason() : null,
                text(identity.getIdentSubmittedAt()), text(identity.getIdentVerifiedAt()), text(identity.getLastSyncAt()));
    }

    private String resolveLocalStatus(FadadaUserIdentityDO identity, boolean confirmed) {
        if (confirmed) return "VERIFIED";
        if ("failed".equalsIgnoreCase(identity.getIdentProcessStatus())) return "FAILED";
        if ("IN_PROGRESS".equals(identity.getLocalStatus()) || hasText(identity.getOpenUserId())
                || "authorized".equalsIgnoreCase(identity.getBindingStatus())) return "IN_PROGRESS";
        return "NOT_STARTED";
    }

    private String statusText(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> "认证中";
            case "VERIFIED" -> "已认证";
            case "FAILED" -> "认证未通过";
            default -> "待认证";
        };
    }

    private void requireReady() {
        if (!properties.isEnabled()) throw new BusinessException("个人认证服务尚未启用");
        if (!hasText(properties.getAppId()) || !hasText(properties.getAppSecret())
                || !hasText(properties.getServerUrl())) {
            throw new BusinessException("个人认证服务配置不完整");
        }
    }

    private String callbackUrl() {
        if (!hasText(properties.getCallbackUrl())) return null;
        String value = properties.getCallbackUrl().trim();
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !hasText(uri.getHost())
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("认证回调地址配置不正确，请联系管理员检查 HTTPS 地址");
        }
    }

    private void validateAuthUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !hasText(uri.getHost())) {
                throw new BusinessException("认证服务返回的个人认证地址不安全");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("认证服务返回的个人认证地址格式不正确");
        }
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("认证授权范围序列化失败", exception);
        }
    }

    private LocalDateTime parseTime(String value, LocalDateTime fallback) {
        return com.tradepass.framework.fadada.core.FadadaProviderTime.parse(value, fallback);
    }

    private String text(LocalDateTime value) {
        return value == null ? null : DISPLAY_TIME.format(value);
    }

    private LocalDateTime beijingNow() {
        return LocalDateTime.now(BEIJING_ZONE);
    }

    private String callbackText(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && hasText(value.asText())) return value.asText().trim();
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
