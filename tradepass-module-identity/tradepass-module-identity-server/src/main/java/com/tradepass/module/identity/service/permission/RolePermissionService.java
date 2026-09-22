package com.tradepass.module.identity.service.permission;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

public interface RolePermissionService {
    public record RoleDef(String text, List<String> permissions) {
        }

    RoleDef role(String code);
    String roleText(String code);
}
