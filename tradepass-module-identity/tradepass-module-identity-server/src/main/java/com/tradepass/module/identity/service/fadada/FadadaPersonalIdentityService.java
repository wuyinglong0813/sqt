package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.fadada.core.FadadaUserQueryException;
import com.tradepass.module.identity.api.fadada.FadadaPersonalIdentityOperations;
import com.tradepass.module.identity.api.fadada.FadadaPersonalIdentityOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.api.fadada.dto.FadadaAuthUrlRespDTO;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaUserIdentityDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.framework.fadada.core.FadadaUserGateway;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public interface FadadaPersonalIdentityService {
    PersonalIdentityRespDTO current();
    PersonalIdentityRespDTO syncCurrent();
    PersonalIdentityRespDTO requireCurrentVerified();
    String verifiedOpenUserId(long userId);
    FadadaAuthUrlRespDTO createAuthUrl();
    PersonalIdentityRespDTO syncByClientUserId(String clientUserId);
    PersonalIdentityRespDTO syncCallback(String clientUserId, JsonNode data);
}
