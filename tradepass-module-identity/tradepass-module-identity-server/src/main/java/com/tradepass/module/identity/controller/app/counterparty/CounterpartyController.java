package com.tradepass.module.identity.controller.app.counterparty;

import com.tradepass.framework.common.pojo.TradePassDtos;

import com.tradepass.module.identity.service.counterparty.CounterpartyService;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.pojo.TradePassDtos.CounterpartyRelation;
import com.tradepass.module.identity.controller.app.counterparty.vo.AddCounterpartyReqVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api")
public class CounterpartyController {
    private final CounterpartyService tradeService;

    public CounterpartyController(CounterpartyService tradeService) { this.tradeService = tradeService; }

    @GetMapping("/counterparties")
    public ApiResponse<List<CounterpartyRelation>> listCounterparties(@RequestParam(required = false) String companyId,
                                                                      @RequestParam(defaultValue = "buyer") String role) {
        return ApiResponse.ok(tradeService.listCounterparties(companyId, role));
    }

    @PostMapping("/counterparties")
    public ApiResponse<CounterpartyRelation> addCounterparty(@Valid @RequestBody AddCounterpartyReqVO request) {
        return ApiResponse.ok(tradeService.addCounterparty(request));
    }
}
