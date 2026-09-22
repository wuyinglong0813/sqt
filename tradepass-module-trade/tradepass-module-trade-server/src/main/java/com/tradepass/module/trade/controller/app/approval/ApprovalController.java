package com.tradepass.module.trade.controller.app.approval;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.module.trade.service.approval.ApprovalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {
    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/fulfillment")
    public ApiResponse<List<Map<String, Object>>> pendingFulfillment() {
        return ApiResponse.ok(approvalService.pendingFulfillment());
    }

    @GetMapping("/results")
    public ApiResponse<List<Map<String, Object>>> results() {
        return ApiResponse.ok(approvalService.results());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(approvalService.summary());
    }

    @PostMapping("/results/{id}/read")
    public ApiResponse<Void> markResultRead(@PathVariable Long id) {
        approvalService.markResultRead(id);
        return ApiResponse.ok(null);
    }
}
