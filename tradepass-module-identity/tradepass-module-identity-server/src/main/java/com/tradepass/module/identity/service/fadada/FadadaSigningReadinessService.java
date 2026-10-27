package com.tradepass.module.identity.service.fadada;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.api.fadada.dto.SigningReadinessRespDTO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaSigningMemberSyncDO;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaSigningMemberSyncMapper;
import com.tradepass.module.identity.service.permission.AccessControlService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static com.tradepass.framework.common.exception.SigningErrorCodes.ADMIN_REVIEW_REQUIRED;

/** Reads cached preparation state only. It never creates members, links, grants or queue work. */
@Service
public class FadadaSigningReadinessService {
    private static final Set<String> REQUIRED_SCOPES = Set.of(
            "ident_info", "seal_info", "signtask_init", "signtask_info", "signtask_file", "organization");
    private final AccessControlService access;
    private final FadadaPersonalIdentityService personal;
    private final FadadaCorpIdentityMapper identities;
    private final FadadaSigningMemberSyncMapper queue;
    private final FadadaProperties properties;
    private final ObjectMapper json;

    public FadadaSigningReadinessService(AccessControlService access, FadadaPersonalIdentityService personal,
            FadadaCorpIdentityMapper identities, FadadaSigningMemberSyncMapper queue,
            FadadaProperties properties, ObjectMapper json) {
        this.access = access;
        this.personal = personal;
        this.identities = identities;
        this.queue = queue;
        this.properties = properties;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public SigningReadinessRespDTO current(long companyId) {
        long userId = AuthContext.userId();
        if (companyId <= 0 || !access.isActiveMember(companyId, userId)) {
            throw new BusinessException(ADMIN_REVIEW_REQUIRED, "该账号不是企业的有效成员");
        }
        var role = access.effectiveRole(companyId, userId);
        Set<String> permissions = role == null || role.permissions() == null
                ? Set.of() : new HashSet<>(role.permissions());
        if (!properties.isEnabled() || !allowed(permissions, "contract_sign")) {
            return result("NOT_REQUIRED", "", "none", false);
        }
        boolean manageSeal = allowed(permissions, "seal_manage")
                && (allowed(permissions, "member_manage") || allowed(permissions, "auth_manage"));
        boolean manageCompany = allowed(permissions, "company_manage");
        var person = personal.current();
        if (person == null || !"VERIFIED".equals(person.status())
                || !"authorized".equalsIgnoreCase(person.bindingStatus())
                || !"identified".equalsIgnoreCase(person.identStatus())) {
            return result("WAITING_PERSONAL_IDENTITY", "请先完成本人实名认证，再准备合同签署", "personal", manageSeal);
        }
        var identity = identities.selectOne(new LambdaQueryWrapper<FadadaCorpIdentityDO>()
                .eq(FadadaCorpIdentityDO::getCompanyId, companyId));
        if (!companyAuthorized(identity)) return companyAction(manageCompany, manageSeal);
        var work = queue.selectOne(new LambdaQueryWrapper<FadadaSigningMemberSyncDO>()
                .eq(FadadaSigningMemberSyncDO::getCompanyId, companyId)
                .eq(FadadaSigningMemberSyncDO::getUserId, userId));
        if (work == null) return pending(false, manageSeal);
        return switch (work.getStatus() == null ? "" : work.getStatus()) {
            case "SIGNING_READY" -> Integer.valueOf(0).equals(work.getLastErrorCode())
                    ? result("READY", "签署准备已完成", "none", manageSeal) : pending(true, manageSeal);
            case "ACTIVATION_REQUIRED" -> result("ACTIVATION_REQUIRED",
                    "请本人确认加入电子签企业，完成后即可继续准备签署", "member", manageSeal);
            case "WAITING_COMPANY_AUTHORIZATION" -> companyAction(manageCompany, manageSeal);
            case "WAITING_SEAL_AUTHORIZATION" -> result("WAITING_SEAL_AUTHORIZATION",
                    manageSeal ? "用印授权待确认，可进入官方页面办理，实际办理权限由电子签平台核验"
                            : "本人用印授权待确认，请联系企业授权人完成办理",
                    manageSeal ? "sealGrant" : "none", manageSeal);
            case "WAITING_SEAL_CONFIGURATION" -> result("ADMIN_REVIEW_REQUIRED",
                    "当前企业尚未设置签署印章，请联系企业管理员处理", "none", manageSeal);
            case "ADMIN_REVIEW_REQUIRED" -> result("ADMIN_REVIEW_REQUIRED",
                    "签署条件需企业管理员核对，可重新检查当前状态", "recheck", manageSeal);
            case "SYNCING" -> result("SYNCING", "后台正在准备本人的签署权限，请稍后查看", "none", manageSeal);
            // Legacy READY attested membership only; freshly completed personal identity also needs reconciliation.
            case "READY", "WAITING_PERSONAL_IDENTITY" -> pending(false, manageSeal);
            case "PENDING" -> pending(work.getLastErrorCode() != null && work.getLastErrorCode() != 0, manageSeal);
            default -> result("ADMIN_REVIEW_REQUIRED", "签署准备状态待核对，可重新检查", "recheck", manageSeal);
        };
    }

    private SigningReadinessRespDTO companyAction(boolean manageCompany, boolean manageSeal) {
        return result("WAITING_COMPANY_AUTHORIZATION",
                manageCompany ? "请补齐企业电子签授权，官方页面会核验办理权限"
                        : "企业电子签授权待补齐，请联系企业管理员办理",
                manageCompany ? "company" : "none", manageSeal);
    }

    private SigningReadinessRespDTO pending(boolean failed, boolean manageSeal) {
        return result("PENDING", failed ? "签署准备暂未完成，可重新检查"
                : "后台正在准备本人的签署权限，请稍后查看", failed ? "recheck" : "none", manageSeal);
    }

    private boolean companyAuthorized(FadadaCorpIdentityDO identity) {
        if (identity == null || !"VERIFIED".equals(identity.getLocalStatus())
                || !"identified".equalsIgnoreCase(identity.getIdentStatus())
                || !"authorized".equalsIgnoreCase(identity.getBindingStatus())
                || identity.getOpenCorpId() == null || identity.getOpenCorpId().isBlank()
                || identity.getAuthScopes() == null) return false;
        try {
            var values = json.readTree(identity.getAuthScopes());
            if (values == null || !values.isArray()) return false;
            var scopes = new HashSet<String>();
            for (var value : values) {
                if (!value.isTextual()) return false;
                scopes.add(value.textValue());
            }
            return scopes.containsAll(REQUIRED_SCOPES);
        } catch (JsonProcessingException invalid) {
            return false;
        }
    }

    private boolean allowed(Set<String> permissions, String permission) {
        return permissions.contains("all") || permissions.contains(permission);
    }

    private SigningReadinessRespDTO result(String status, String message, String action, boolean manageSeal) {
        return new SigningReadinessRespDTO(status, message, action, manageSeal);
    }
}
