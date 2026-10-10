package com.tradepass.module.contract.controller.app.membership;

import com.tradepass.module.contract.service.membership.MembershipPurchaseService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/membership/payment")
public class MembershipPaymentCallbackController {
    private final MembershipPurchaseService purchases;
    public MembershipPaymentCallbackController(MembershipPurchaseService purchases) { this.purchases=purchases; }
    @PostMapping(value="/notify",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> notifyPayment(@RequestHeader HttpHeaders headers,@RequestBody String body) {
        try {
            purchases.notifyPayment(headers.toSingleValueMap(),body);
            return ResponseEntity.noContent().build();
        } catch(IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code","FAIL","message","支付通知验证失败"));
        } catch(RuntimeException e) {
            return ResponseEntity.status(500).body(Map.of("code","FAIL","message","支付通知处理未完成"));
        }
    }
}
