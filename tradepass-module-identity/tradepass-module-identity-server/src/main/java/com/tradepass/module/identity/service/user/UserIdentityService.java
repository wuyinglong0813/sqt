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

public interface UserIdentityService {
    void setIdentityMapper(FadadaUserIdentityMapper identityMapper);
    String currentDisplayName();
    String requireCurrentVerifiedName(long companyId);
}
