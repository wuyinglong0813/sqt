package com.tradepass.module.identity.service.directory;

import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class IdentityDirectoryServiceImpl implements IdentityDirectoryService {
    private final JdbcTemplate jdbc;
    public IdentityDirectoryServiceImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Map<Long, String> companyNames(List<Long> ids) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return result;
        for (int offset = 0; offset < ids.size(); offset += 200) {
            List<Long> batch = ids.subList(offset, Math.min(offset + 200, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            jdbc.query("SELECT id, name FROM company WHERE id IN (" + placeholders + ")", rs -> {
                result.put(rs.getLong("id"), rs.getString("name"));
            }, batch.toArray());
        }
        return result;
    }
    public List<Long> companyIdsNamed(String name) {
        return jdbc.queryForList("SELECT id FROM company WHERE name = ? ORDER BY id", Long.class, name);
    }
    public String userDisplayName(long userId) {
        var names = jdbc.queryForList("SELECT COALESCE(nickname, phone, CONCAT('用户', id)) FROM sys_user WHERE id = ?", String.class, userId);
        return names.isEmpty() ? "用户" + userId : names.get(0);
    }
    public Map<Long, String> userNicknames(List<Long> ids) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return result;
        for (int offset = 0; offset < ids.size(); offset += 200) {
            List<Long> batch = ids.subList(offset, Math.min(offset + 200, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            jdbc.query("SELECT id, nickname FROM sys_user WHERE id IN (" + placeholders + ")", rs -> {
                result.put(rs.getLong("id"), rs.getString("nickname"));
            }, batch.toArray());
        }
        return result;
    }
    public List<Counterparty> counterparties(long companyId) {
        return jdbc.query("""
                SELECT DISTINCT pair.counterparty_id, company.name AS counterparty_name
                FROM (
                    SELECT CASE WHEN relation.company_id = ?
                                THEN relation.counterparty_company_id ELSE relation.company_id END AS counterparty_id
                    FROM counterparty_relation relation
                    WHERE relation.status = 'ACTIVE'
                      AND (relation.company_id = ? OR relation.counterparty_company_id = ?)
                      AND relation.counterparty_company_id IS NOT NULL
                ) pair
                JOIN company ON company.id = pair.counterparty_id
                ORDER BY company.name, pair.counterparty_id
                """, (rs, row) -> new Counterparty(rs.getLong("counterparty_id"), rs.getString("counterparty_name")), companyId, companyId, companyId);
    }
    public List<String> orderedCompanyNames(long companyAId, long companyBId) {
        return jdbc.queryForList("SELECT name FROM company WHERE id IN (?, ?) ORDER BY id", String.class, companyAId, companyBId);
    }
}
