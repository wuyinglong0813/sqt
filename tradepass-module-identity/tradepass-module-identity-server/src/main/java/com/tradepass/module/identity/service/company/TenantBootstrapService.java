package com.tradepass.module.identity.service.company;

import com.tradepass.module.contract.api.tenant.ContractTenantOperations;
import com.tradepass.module.contract.api.tenant.ContractTenantOperations.*;
import com.tradepass.module.trade.api.document.DocumentTenantOperations;
import com.tradepass.module.trade.api.document.DocumentTenantOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface TenantBootstrapService {
    public record SeedRole(String name, List<String> permissions) {
        }

    void initialize(long companyId, long operatorUserId);
    Map<String, SeedRole> standardRolesView();
}
