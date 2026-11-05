package com.tradepass.module.identity.service.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaUserIdentityDO;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import org.springframework.stereotype.Service;

/** Server-confirmed identity, checked locally on every business access. */
@Service
public class PersonalIdentityAccess {
    private final FadadaUserIdentityMapper identities;

    public PersonalIdentityAccess(FadadaUserIdentityMapper identities) {
        this.identities = identities;
    }

    public boolean isVerified(long userId) {
        FadadaUserIdentityDO identity = identities.selectOne(new LambdaQueryWrapper<FadadaUserIdentityDO>()
                .eq(FadadaUserIdentityDO::getUserId, userId).last("LIMIT 1"));
        return identity != null && "VERIFIED".equals(identity.getLocalStatus())
                && identity.getOpenUserId() != null && !identity.getOpenUserId().isBlank();
    }
}
