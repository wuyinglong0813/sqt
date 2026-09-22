package com.tradepass.module.identity.service.auth;

import com.tradepass.framework.cache.core.RedisCacheService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.module.identity.dal.dataobject.auth.AuthSessionDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.mysql.auth.AuthSessionMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

public interface AuthSessionService {
    String issue(long userId);
    Long resolveUserId(String authorization);
    void revoke(String authorization);
}
