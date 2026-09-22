package com.tradepass.module.trade.controller.app.ledger;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.service.ledger.ProjectLedgerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/project-ledgers")
public class ProjectLedgerController {
    private final ProjectLedgerService projectLedgerService;

    public ProjectLedgerController(ProjectLedgerService projectLedgerService) {
        this.projectLedgerService = projectLedgerService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(projectLedgerService.listProjects());
    }

    @GetMapping("/contracts/{contractId:\\d+}/assignment")
    public ApiResponse<Map<String, Object>> contractAssignment(@PathVariable Long contractId) {
        return ApiResponse.ok(projectLedgerService.contractAssignment(contractId));
    }

    @PostMapping("/contracts/{contractId:\\d+}/dismiss")
    public ApiResponse<Map<String, Object>> dismissContractPrompt(@PathVariable Long contractId) {
        return ApiResponse.ok(projectLedgerService.dismissContractPrompt(contractId));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(projectLedgerService.createProject(
                string(body.get("projectNo")), string(body.get("name")), string(body.get("description"))));
    }

    @GetMapping("/{id:\\d+}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(projectLedgerService.project(id));
    }

    @GetMapping("/{id:\\d+}/available-contracts")
    public ApiResponse<List<Map<String, Object>>> availableContracts(@PathVariable Long id) {
        return ApiResponse.ok(projectLedgerService.availableContracts(id));
    }

    @PostMapping("/{id:\\d+}/contracts")
    public ApiResponse<Map<String, Object>> assignContracts(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(projectLedgerService.assignContracts(id, longList(body.get("contractIds"))));
    }

    @PostMapping("/{id:\\d+}/contracts/{contractId:\\d+}/remove")
    public ApiResponse<Map<String, Object>> removeContract(@PathVariable Long id,
                                                           @PathVariable Long contractId) {
        return ApiResponse.ok(projectLedgerService.removeContract(id, contractId));
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> values)) throw new BusinessException("请选择需要划分的合同");
        try {
            return values.stream().map(item -> Long.valueOf(String.valueOf(item))).toList();
        } catch (NumberFormatException exception) {
            throw new BusinessException("合同 ID 格式不正确");
        }
    }
}
