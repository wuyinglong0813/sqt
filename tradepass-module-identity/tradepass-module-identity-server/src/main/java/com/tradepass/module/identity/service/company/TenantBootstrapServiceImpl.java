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

@Service
public class TenantBootstrapServiceImpl implements TenantBootstrapService {
    private static final Map<String, SeedRole> STANDARD_ROLES = standardRoles();
    private final RoleDefMapper roleDefMapper;
    private final ObjectMapper objectMapper;
    private final com.tradepass.module.contract.api.tenant.ContractTenantOperations contractTemplates;
    private final com.tradepass.module.trade.api.document.DocumentTenantOperations documentTemplates;

    public TenantBootstrapServiceImpl(RoleDefMapper roles, ObjectMapper objectMapper,
            com.tradepass.module.contract.api.tenant.ContractTenantOperations contractTemplates,
            com.tradepass.module.trade.api.document.DocumentTenantOperations documentTemplates) {
        this.roleDefMapper = roles;
        this.objectMapper = objectMapper;
        this.contractTemplates = contractTemplates;
        this.documentTemplates = documentTemplates;
    }

    public void initialize(long companyId, long operatorUserId) {
        STANDARD_ROLES.forEach((code, seed) -> seedRole(companyId, code, seed));
        contractTemplates.initialize(companyId, operatorUserId);
        documentTemplates.initialize(companyId, operatorUserId);
    }

    public Map<String, SeedRole> standardRolesView() {
        return STANDARD_ROLES;
    }

    private void seedRole(long companyId, String code, SeedRole seed) {
        RoleDefDO existing = roleDefMapper.selectOne(new LambdaQueryWrapper<RoleDefDO>()
                .eq(RoleDefDO::getCompanyId, companyId)
                .eq(RoleDefDO::getCode, code)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        RoleDefDO role = new RoleDefDO();
        role.setCompanyId(companyId);
        role.setCode(code);
        role.setName(seed.name());
        role.setSystemRole(true);
        role.setPermissions(toJson(seed.permissions()));
        roleDefMapper.insert(role);
    }

    private String toJson(List<String> permissions) {
        try {
            return objectMapper.writeValueAsString(permissions);
        } catch (Exception e) {
            throw new BusinessException("标准角色初始化失败");
        }
    }

    private static Map<String, SeedRole> standardRoles() {
        Map<String, SeedRole> roles = new LinkedHashMap<>();
        roles.put("LEGAL", new SeedRole("法人", List.of("all")));
        roles.put("ADMIN", new SeedRole("管理员", List.of("member_manage", "auth_manage", "company_manage", "seal_manage", "contract_template", "counterparty_view")));
        roles.put("SALES", new SeedRole("销售员", List.of("supplier_view", "counterparty_manage", "order_view", "order_create", "contract_sign", "contract_view", "reconciliation", "contract_attachment_upload", "counterparty_view")));
        roles.put("PURCHASER", new SeedRole("采购员", List.of("buyer_view", "order_create", "contract_view", "order_view", "contract_sign", "reconciliation", "contract_attachment_upload", "sales_order_receive", "inventory_view", "inventory_receive", "counterparty_view")));
        roles.put("FINANCE", new SeedRole("财务", List.of("invoice_view", "reconciliation", "contract_attachment_upload", "counterparty_view")));
        return Map.copyOf(roles);
    }

    
}
