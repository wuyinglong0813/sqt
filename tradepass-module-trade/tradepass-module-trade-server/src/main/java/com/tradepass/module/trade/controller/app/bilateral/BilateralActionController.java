package com.tradepass.module.trade.controller.app.bilateral;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.service.bilateral.BilateralActionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/bilateral-actions")
public class BilateralActionController {
    private final BilateralActionService actionService;

    public BilateralActionController(BilateralActionService actionService) {
        this.actionService = actionService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> request(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(actionService.request(
                string(body.get("bizType")), longValue(body.get("bizId")),
                string(body.get("actionType")), string(body.get("reason")),
                booleanValue(body.get("riskConfirmed"))));
    }

    @GetMapping("/active")
    public ApiResponse<Map<String, Object>> active(@RequestParam String bizType,
                                                   @RequestParam Long bizId) {
        return ApiResponse.ok(actionService.active(bizType, bizId));
    }

    @PostMapping("/{id}/decision")
    public ApiResponse<Map<String, Object>> decide(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(actionService.decide(id, string(body.get("decision")),
                string(body.get("reason"))));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<String> cancel(@PathVariable Long id) {
        return ApiResponse.ok(actionService.cancel(id));
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new BusinessException("业务 ID 格式不正确");
        }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(string(value));
    }
}
