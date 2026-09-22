package com.tradepass.module.identity.service.user;

import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations.*;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaUserIdentityDO;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserIdentityServiceImpl implements UserIdentityService {
    private final SysUserMapper userMapper;
    private final CompanyMapper companyMapper;
    private FadadaUserIdentityMapper identityMapper;

    public UserIdentityServiceImpl(SysUserMapper userMapper, CompanyMapper companyMapper) {
        this.userMapper = userMapper;
        this.companyMapper = companyMapper;
    }

    @Autowired
    public void setIdentityMapper(FadadaUserIdentityMapper identityMapper) {
        this.identityMapper = identityMapper;
    }

    public String currentDisplayName() {
        long userId = AuthContext.userId();
        SysUserDO user = userMapper.selectById(userId);
        if (user == null) return "用户" + userId;
        String nickname = safe(user.getNickname());
        if (!nickname.isBlank()) return trim(nickname, 64);
        String phone = safe(user.getPhone());
        return phone.isBlank() ? "用户" + userId : trim(phone, 64);
    }

    public String requireCurrentVerifiedName(long companyId) {
        if (identityMapper == null) return currentDisplayName();
        FadadaUserIdentityDO identity = identityMapper.selectOne(new LambdaQueryWrapper<FadadaUserIdentityDO>()
                .eq(FadadaUserIdentityDO::getUserId, AuthContext.userId()).last("LIMIT 1"));
        if (identity == null || !"VERIFIED".equals(identity.getLocalStatus())
                || identity.getVerifiedName() == null || identity.getVerifiedName().isBlank()) {
            throw new BusinessException("请先完成个人认证，再确认业务单据");
        }
        return trim(identity.getVerifiedName(), 64);
    }

    private String trim(String value, int maxLength) {
        String text = value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
