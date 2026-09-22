package com.tradepass.module.trade.service.ledger;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations.*;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;

import com.tradepass.framework.mybatis.core.ApplicationIds;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectLedgerServiceImpl implements ProjectLedgerService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.contract.api.directory.ContractDirectoryOperations contractDirectory;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.identity.api.directory.IdentityDirectoryOperations identityDirectory;
    private final JdbcTemplate jdbc;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;

    public ProjectLedgerServiceImpl(JdbcTemplate jdbc,
                                AccessControlOperations accessControlService,
                                AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
    }

    public List<Map<String, Object>> listProjects() {
        long companyId = requireManager();
        return projectRows(companyId, null);
    }

    public Map<String, Object> project(Long projectId) {
        long companyId = requireManager();
        Map<String, Object> project = requireProject(companyId, projectId);
        project.put("contracts", assignedContracts(companyId, projectId));
        return project;
    }

    public Map<String, Object> contractAssignment(Long contractId) {
        long companyId = requireManager();
        requireActivePartyContract(companyId, contractId);

        List<Map<String, Object>> assignments = jdbc.query("""
                        SELECT project.id, project.project_no, project.name
                        FROM project_contract_assignment assignment
                        JOIN project_ledger project ON project.id = assignment.project_id
                        WHERE assignment.company_id = ? AND assignment.contract_id = ?
                        LIMIT 1
                        """, (rs, rowNum) -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("assigned", true);
                    view.put("dismissed", false);
                    view.put("projectId", rs.getLong("id"));
                    view.put("projectNo", rs.getString("project_no"));
                    view.put("projectName", rs.getString("name"));
                    return view;
                }, companyId, contractId);
        if (!assignments.isEmpty()) return assignments.get(0);
        Long dismissedCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_contract_prompt_preference
                WHERE company_id = ? AND contract_id = ?
                """, Long.class, companyId, contractId);
        Map<String, Object> unassigned = new LinkedHashMap<>();
        unassigned.put("assigned", false);
        unassigned.put("dismissed", dismissedCount != null && dismissedCount > 0);
        return unassigned;
    }

    @Transactional
    public Map<String, Object> dismissContractPrompt(Long contractId) {
        long companyId = requireManager();
        requireActivePartyContract(companyId, contractId);
        jdbc.update("""
                INSERT IGNORE INTO project_contract_prompt_preference
                (id, company_id, contract_id, dismissed_by)
                VALUES (?, ?, ?, ?)
                """, ApplicationIds.next(), companyId, contractId, AuthContext.userId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dismissed", true);
        return result;
    }

    private void requireActivePartyContract(long companyId, Long contractId) {
        if (contractId == null) throw new BusinessException("合同不存在");
        Long validContractId = contractDirectory != null ? contractDirectory.activePartyContractId(companyId, contractId) : jdbc.query("""
                        SELECT id FROM trade_contract
                        WHERE id = ? AND status = 'ACTIVE'
                          AND (company_id = ? OR counterparty_company_id = ?)
                        LIMIT 1
                        """, rs -> rs.next() ? rs.getLong(1) : null,
                contractId, companyId, companyId);
        if (validContractId == null) throw new BusinessException("合同不存在或尚未签署生效");
    }

    @Transactional
    public Map<String, Object> createProject(String projectNo, String name, String description) {
        long companyId = requireManager();
        String safeName = clean(name);
        String safeNo = clean(projectNo);
        String safeDescription = clean(description);
        if (safeName.isBlank() || safeName.length() > 128) {
            throw new BusinessException("项目名称不能为空且不能超过 128 字");
        }
        if (safeNo.isBlank()) safeNo = createProjectNo();
        if (safeNo.length() > 64) throw new BusinessException("项目编号不能超过 64 字");
        if (safeDescription.length() > 500) throw new BusinessException("项目说明不能超过 500 字");
        Long projectId = ApplicationIds.next();
        try {
            jdbc.update("""
                    INSERT INTO project_ledger
                    (id, company_id, project_no, name, description, created_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, projectId, companyId, safeNo, safeName, emptyToNull(safeDescription), AuthContext.userId());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("项目名称或编号已存在");
        }
        if (projectId == null) throw new BusinessException("项目创建失败");
        auditLogService.log(companyId, "PROJECT_LEDGER", projectId, "CREATE",
                "创建项目 " + safeName + "（" + safeNo + "）");
        return requireProject(companyId, projectId);
    }

    public List<Map<String, Object>> availableContracts(Long projectId) {
        long companyId = requireManager();
        requireProject(companyId, projectId);
        if (contractDirectory != null) {
            var assigned = new java.util.HashSet<>(jdbc.queryForList(
                    "SELECT contract_id FROM project_contract_assignment WHERE company_id = ?", Long.class, companyId));
            var contracts = contractDirectory.activePartyContracts(companyId).stream()
                    .filter(contract -> !assigned.contains(contract.getId())).toList();
            return contractViews(companyId, contracts);
        }
        return jdbc.query("""
                        SELECT contract.id, contract.contract_no, contract.name, contract.amount,
                               contract.start_date, contract.end_date,
                               CASE WHEN contract.company_id = ?
                                    THEN contract.counterparty_name ELSE initiator.name END AS counterparty_name,
                               CASE WHEN contract.company_id = ? THEN UPPER(contract.direction)
                                    WHEN UPPER(contract.direction) = 'PURCHASE' THEN 'SALE'
                                    ELSE 'PURCHASE' END AS viewer_direction
                        FROM trade_contract contract
                        JOIN company initiator ON initiator.id = contract.company_id
                        WHERE contract.status = 'ACTIVE'
                          AND (contract.company_id = ? OR contract.counterparty_company_id = ?)
                          AND NOT EXISTS (
                              SELECT 1 FROM project_contract_assignment assignment
                              WHERE assignment.company_id = ? AND assignment.contract_id = contract.id
                          )
                        ORDER BY COALESCE(contract.approved_at, contract.created_at) DESC, contract.id DESC
                        """, (rs, rowNum) -> contractView(
                        rs.getLong("id"), rs.getString("contract_no"), rs.getString("name"),
                        rs.getString("counterparty_name"), rs.getString("viewer_direction"),
                        rs.getBigDecimal("amount"), rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class), "ACTIVE"),
                companyId, companyId, companyId, companyId, companyId);
    }

    @Transactional
    public Map<String, Object> assignContracts(Long projectId, List<Long> contractIds) {
        long companyId = requireManager();
        Map<String, Object> project = requireProject(companyId, projectId);
        if (!"ACTIVE".equals(project.get("status"))) throw new BusinessException("已停用项目不能划分合同");
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        if (contractIds != null) uniqueIds.addAll(contractIds);
        uniqueIds.remove(null);
        if (uniqueIds.isEmpty()) throw new BusinessException("请选择需要划分的合同");
        if (uniqueIds.size() > 100) throw new BusinessException("每次最多划分 100 份合同");

        for (Long contractId : uniqueIds) {
            Long valid = contractDirectory != null ? contractDirectory.activePartyContractId(companyId, contractId) : jdbc.query("""
                            SELECT id FROM trade_contract
                            WHERE id = ? AND status = 'ACTIVE'
                              AND (company_id = ? OR counterparty_company_id = ?)
                            LIMIT 1
                            """, rs -> rs.next() ? rs.getLong(1) : null,
                    contractId, companyId, companyId);
            if (valid == null) throw new BusinessException("合同不存在或尚未签署生效");
            try {
                jdbc.update("""
                        INSERT INTO project_contract_assignment
                        (id, company_id, project_id, contract_id, created_by)
                        VALUES (?, ?, ?, ?, ?)
                        """, ApplicationIds.next(), companyId, projectId, contractId, AuthContext.userId());
            } catch (DuplicateKeyException exception) {
                throw new BusinessException("所选合同已划入其他项目");
            }
        }
        auditLogService.log(companyId, "PROJECT_LEDGER", projectId, "ASSIGN_CONTRACT",
                "为项目划分合同 " + uniqueIds);
        return project(projectId);
    }

    @Transactional
    public Map<String, Object> removeContract(Long projectId, Long contractId) {
        long companyId = requireManager();
        requireProject(companyId, projectId);
        int removed = jdbc.update("""
                DELETE FROM project_contract_assignment
                WHERE company_id = ? AND project_id = ? AND contract_id = ?
                """, companyId, projectId, contractId);
        if (removed == 0) throw new BusinessException("该合同未划入当前项目");
        auditLogService.log(companyId, "PROJECT_LEDGER", projectId, "REMOVE_CONTRACT",
                "从项目移出合同 " + contractId);
        return project(projectId);
    }

    private long requireManager() {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireManager(companyId);
        return companyId;
    }

    private Map<String, Object> requireProject(long companyId, Long projectId) {
        if (projectId == null) throw new BusinessException("项目不存在");
        List<Map<String, Object>> projects = projectRows(companyId, projectId);
        if (projects.isEmpty()) throw new BusinessException("项目不存在");
        return new LinkedHashMap<>(projects.get(0));
    }

    private List<Map<String, Object>> projectRows(long companyId, Long projectId) {
        if (contractDirectory != null) return ownedProjectRows(companyId, projectId);
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        args.add(companyId);
        args.add(companyId);
        args.add(companyId);
        args.add(companyId);
        if (projectId != null) {
            args.add(projectId);
        }
        String projectFilter = projectId == null ? "" : " AND project.id = ?\n";
        return jdbc.query("""
                        SELECT project.id, project.project_no, project.name, project.description,
                               project.status, project.created_at, project.updated_at,
                               COUNT(assignment.id) AS contract_count,
                               COALESCE(SUM(CASE WHEN contract.status = 'ACTIVE' AND (
                                   (contract.company_id = ? AND UPPER(contract.direction) = 'PURCHASE') OR
                                   (contract.counterparty_company_id = ? AND UPPER(contract.direction) = 'SALE')
                               ) THEN contract.amount ELSE 0 END), 0) AS purchase_cost,
                               COALESCE(SUM(CASE WHEN contract.status = 'ACTIVE' AND (
                                   (contract.company_id = ? AND UPPER(contract.direction) = 'SALE') OR
                                   (contract.counterparty_company_id = ? AND UPPER(contract.direction) = 'PURCHASE')
                               ) THEN contract.amount ELSE 0 END), 0) AS sales_income
                        FROM project_ledger project
                        LEFT JOIN project_contract_assignment assignment
                          ON assignment.project_id = project.id AND assignment.company_id = project.company_id
                        LEFT JOIN trade_contract contract ON contract.id = assignment.contract_id
                        WHERE project.company_id = ?
                        """ + projectFilter + """
                        GROUP BY project.id, project.project_no, project.name, project.description,
                                 project.status, project.created_at, project.updated_at
                        ORDER BY project.created_at DESC, project.id DESC
                        """, (rs, rowNum) -> {
                    BigDecimal purchaseCost = money(rs.getBigDecimal("purchase_cost"));
                    BigDecimal salesIncome = money(rs.getBigDecimal("sales_income"));
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", rs.getLong("id"));
                    view.put("projectNo", rs.getString("project_no"));
                    view.put("name", rs.getString("name"));
                    view.put("description", safe(rs.getString("description")));
                    view.put("status", rs.getString("status"));
                    view.put("contractCount", rs.getLong("contract_count"));
                    view.put("purchaseCost", purchaseCost);
                    view.put("salesIncome", salesIncome);
                    view.put("estimatedProfit", money(salesIncome.subtract(purchaseCost)));
                    view.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    view.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
                    return view;
                }, args.toArray());
    }

    private List<Map<String, Object>> assignedContracts(long companyId, Long projectId) {
        if (contractDirectory != null) {
            var ids = jdbc.queryForList("""
                    SELECT contract_id FROM project_contract_assignment
                    WHERE company_id = ? AND project_id = ? ORDER BY created_at DESC, id DESC
                    """, Long.class, companyId, projectId);
            var byId = contractDirectory.contractsByIds(ids).stream().collect(
                    java.util.stream.Collectors.toMap(com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO::getId, java.util.function.Function.identity()));
            return contractViews(companyId, ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList());
        }
        return jdbc.query("""
                        SELECT contract.id, contract.contract_no, contract.name, contract.amount,
                               contract.start_date, contract.end_date, contract.status,
                               CASE WHEN contract.company_id = ?
                                    THEN contract.counterparty_name ELSE initiator.name END AS counterparty_name,
                               CASE WHEN contract.company_id = ? THEN UPPER(contract.direction)
                                    WHEN UPPER(contract.direction) = 'PURCHASE' THEN 'SALE'
                                    ELSE 'PURCHASE' END AS viewer_direction
                        FROM project_contract_assignment assignment
                        JOIN trade_contract contract ON contract.id = assignment.contract_id
                        JOIN company initiator ON initiator.id = contract.company_id
                        WHERE assignment.company_id = ? AND assignment.project_id = ?
                        ORDER BY assignment.created_at DESC, assignment.id DESC
                        """, (rs, rowNum) -> contractView(
                        rs.getLong("id"), rs.getString("contract_no"), rs.getString("name"),
                        rs.getString("counterparty_name"), rs.getString("viewer_direction"),
                        rs.getBigDecimal("amount"), rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class), rs.getString("status")),
                companyId, companyId, companyId, projectId);
    }

    private Map<String, Object> contractView(long id, String contractNo, String name,
                                             String counterpartyName, String direction,
                                             BigDecimal amount, LocalDate startDate,
                                             LocalDate endDate, String status) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("contractNo", safe(contractNo));
        view.put("name", safe(name));
        view.put("counterpartyName", safe(counterpartyName));
        view.put("direction", direction == null ? "" : direction.toUpperCase(Locale.ROOT));
        view.put("directionText", "PURCHASE".equalsIgnoreCase(direction) ? "采购合同" : "销售合同");
        view.put("amount", money(amount));
        view.put("startDate", startDate);
        view.put("endDate", endDate);
        view.put("status", status);
        return view;
    }

    private List<Map<String, Object>> contractViews(long companyId, List<com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO> contracts) {
        var names = identityDirectory.companyNames(contracts.stream().map(com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO::getCompanyId).distinct().toList());
        return contracts.stream().filter(contract -> names.containsKey(contract.getCompanyId())).map(contract -> {
            boolean initiator = Long.valueOf(companyId).equals(contract.getCompanyId());
            String direction = initiator ? contract.getDirection()
                    : "PURCHASE".equalsIgnoreCase(contract.getDirection()) ? "SALE" : "PURCHASE";
            return contractView(contract.getId(), contract.getContractNo(), contract.getName(),
                    initiator ? contract.getCounterpartyName() : names.get(contract.getCompanyId()), direction,
                    contract.getAmount(), contract.getStartDate(), contract.getEndDate(), contract.getStatus());
        }).toList();
    }

    private List<Map<String, Object>> ownedProjectRows(long companyId, Long projectId) {
        String filter = projectId == null ? "" : " AND id = ?";
        Object[] parameters = projectId == null ? new Object[]{companyId} : new Object[]{companyId, projectId};
        var projects = jdbc.query("SELECT * FROM project_ledger WHERE company_id = ?" + filter + " ORDER BY created_at DESC, id DESC",
                (rs, row) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", rs.getLong("id"));
                    value.put("projectNo", rs.getString("project_no"));
                    value.put("name", rs.getString("name"));
                    value.put("description", safe(rs.getString("description")));
                    value.put("status", rs.getString("status"));
                    value.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    value.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
                    return value;
                }, parameters);
        var assignments = jdbc.query("SELECT project_id, contract_id FROM project_contract_assignment WHERE company_id = ?",
                (rs, row) -> new Long[]{rs.getLong("project_id"), rs.getLong("contract_id")}, companyId);
        var contractIds = assignments.stream().map(pair -> pair[1]).distinct().toList();
        var contracts = contractDirectory.contractsByIds(contractIds).stream().collect(java.util.stream.Collectors.toMap(
                com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO::getId, java.util.function.Function.identity()));
        for (var project : projects) {
            long count = 0;
            BigDecimal purchase = BigDecimal.ZERO, sales = BigDecimal.ZERO;
            for (var assignment : assignments) {
                if (!assignment[0].equals(project.get("id"))) continue;
                count++;
                var contract = contracts.get(assignment[1]);
                if (contract == null || !"ACTIVE".equals(contract.getStatus())) continue;
                BigDecimal amount = contract.getAmount() == null ? BigDecimal.ZERO : contract.getAmount();
                boolean initiator = Long.valueOf(companyId).equals(contract.getCompanyId());
                boolean counterparty = Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId());
                if ((initiator && "PURCHASE".equalsIgnoreCase(contract.getDirection()))
                        || (counterparty && "SALE".equalsIgnoreCase(contract.getDirection()))) purchase = purchase.add(amount);
                if ((initiator && "SALE".equalsIgnoreCase(contract.getDirection()))
                        || (counterparty && "PURCHASE".equalsIgnoreCase(contract.getDirection()))) sales = sales.add(amount);
            }
            purchase = money(purchase); sales = money(sales);
            project.put("contractCount", count);
            project.put("purchaseCost", purchase);
            project.put("salesIncome", sales);
            project.put("estimatedProfit", money(sales.subtract(purchase)));
        }
        return projects;
    }

    private String createProjectNo() {
        return "XM-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
