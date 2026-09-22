package com.tradepass.module.identity.api.permission.dto;

import java.util.List;

public record RoleRespDTO(
        String id,
        String code,
        String name,
        List<String> permissions,
        boolean systemRole,
        boolean editable,
        boolean deletable
) {
}
