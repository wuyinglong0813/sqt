package com.tradepass.module.contract.service.tenant;

import com.tradepass.module.contract.api.tenant.ContractTenantOperations;
import com.tradepass.module.contract.api.tenant.ContractTenantOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.contract.dal.dataobject.template.ContractTemplateDO;
import com.tradepass.module.contract.dal.dataobject.template.TemplateCategoryDO;
import com.tradepass.module.contract.dal.mysql.template.ContractTemplateMapper;
import com.tradepass.module.contract.dal.mysql.template.TemplateCategoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

public interface ContractTenantService {
    void initialize(long companyId, long operatorUserId);
}
