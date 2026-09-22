package com.tradepass.module.identity.service.permission;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RolePermissionServiceImpl implements RolePermissionService {
    

    private static final Map<String, RoleDef> ROLES = Map.of(
            "LEGAL", new RoleDef("法人", List.of("all")),
            "ADMIN", new RoleDef("管理员", List.of("member_manage", "auth_manage", "company_manage", "seal_manage", "contract_template", "counterparty_view")),
            "SALES", new RoleDef("销售员", List.of("supplier_view", "counterparty_manage", "order_view",
                    "order_create", "contract_sign", "contract_view", "reconciliation", "contract_attachment_upload", "counterparty_view")),
            "PURCHASER", new RoleDef("采购员", List.of("buyer_view", "order_create", "contract_view",
                    "order_view", "contract_sign", "reconciliation", "contract_attachment_upload",
                    "sales_order_receive", "inventory_view", "inventory_receive", "counterparty_view")),
            "FINANCE", new RoleDef("财务", List.of("invoice_view", "reconciliation", "contract_attachment_upload", "counterparty_view")),
            "GUEST", new RoleDef("访客", List.of()),
            "LEGAL_CANDIDATE", new RoleDef("企业认证待审核", List.of())
    );

    public RoleDef role(String code) {
        if (code == null || code.isBlank()) {
            return ROLES.get("GUEST");
        }
        return ROLES.getOrDefault(code, new RoleDef(code, List.of()));
    }

    public String roleText(String code) {
        return role(code).text();
    }
}
