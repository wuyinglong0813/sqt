package com.tradepass.module.contract.controller.app.membership;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.module.contract.service.membership.MembershipPurchaseService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/membership")
public class MembershipPurchaseController {
    public record CreateOrder(String productId,String idempotencyKey,String platform) { }
    private final MembershipPurchaseService service;
    public MembershipPurchaseController(MembershipPurchaseService service) { this.service=service; }
    @GetMapping("/products") public ApiResponse<?> products(@RequestParam(defaultValue="unknown")String platform) {
        return ApiResponse.ok(service.catalog(platform));
    }
    @PostMapping("/orders") public ApiResponse<?> create(@RequestBody CreateOrder request) {
        return ApiResponse.ok(service.create(request.productId(),request.idempotencyKey(),request.platform()));
    }
    @GetMapping("/orders") public ApiResponse<?> orders(@RequestParam(defaultValue="unknown")String platform) {
        return ApiResponse.ok(service.orders(platform));
    }
    @GetMapping("/orders/{no}") public ApiResponse<?> order(@PathVariable String no,@RequestParam(defaultValue="unknown")String platform) {
        return ApiResponse.ok(service.detail(no,platform));
    }
    @PostMapping("/orders/{no}/prepay") public ApiResponse<?> prepay(@PathVariable String no,@RequestParam(defaultValue="unknown")String platform) {
        return ApiResponse.ok(service.prepay(no,platform));
    }
    @PostMapping("/orders/{no}/sync") public ApiResponse<?> sync(@PathVariable String no,@RequestParam(defaultValue="unknown")String platform) {
        return ApiResponse.ok(service.sync(no,platform));
    }
    @PostMapping("/orders/{no}/close") public ApiResponse<?> close(@PathVariable String no,@RequestParam(defaultValue="unknown")String platform) {
        return ApiResponse.ok(service.close(no,platform));
    }
}
