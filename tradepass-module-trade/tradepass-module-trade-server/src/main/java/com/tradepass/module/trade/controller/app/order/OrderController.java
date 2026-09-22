package com.tradepass.module.trade.controller.app.order;

import com.tradepass.module.trade.service.order.OrderService;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.module.trade.controller.app.order.vo.CreateOrderReqVO;
import com.tradepass.framework.common.pojo.PagePayload;
import com.tradepass.module.trade.api.order.dto.TradeOrderRespDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderController {
    private final OrderService tradeService;

    public OrderController(OrderService tradeService) { this.tradeService = tradeService; }

    @GetMapping("/orders")
    public ApiResponse<PagePayload<TradeOrderRespDTO>> listOrders(@RequestParam(required = false) String counterpartyName,
                                                                  @RequestParam(required = false) String direction,
                                                                  @RequestParam(defaultValue = "1") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(tradeService.pageOrders(counterpartyName, direction, page, size));
    }

    @GetMapping("/orders/summary")
    public ApiResponse<Map<String, Object>> orderSummary(@RequestParam(required = false) String counterpartyName,
                                                         @RequestParam(required = false) String direction) {
        return ApiResponse.ok(tradeService.orderSummary(counterpartyName, direction));
    }

    @GetMapping("/orders/monthly-summary")
    public ApiResponse<List<Map<String, Object>>> monthlyOrderSummary(@RequestParam String counterpartyName,
                                                                      @RequestParam String direction) {
        return ApiResponse.ok(tradeService.monthlyOrderSummary(counterpartyName, direction));
    }

    @PostMapping("/orders")
    public ApiResponse<TradeOrderRespDTO> createOrder(@Valid @RequestBody CreateOrderReqVO request) {
        return ApiResponse.ok(tradeService.createOrder(request));
    }
}
