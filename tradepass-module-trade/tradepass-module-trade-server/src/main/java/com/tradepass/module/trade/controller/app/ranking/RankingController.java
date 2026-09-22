package com.tradepass.module.trade.controller.app.ranking;

import com.tradepass.framework.common.pojo.TradePassDtos;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.pojo.TradePassDtos.HomePayload;
import com.tradepass.framework.common.pojo.TradePassDtos.RankingItem;
import com.tradepass.module.trade.service.ranking.RankingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class RankingController {
    private final RankingService rankingService;

    public RankingController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping("/home/supplier")
    public ApiResponse<HomePayload> supplierHome(@RequestParam(defaultValue = "total") String period,
                                                 @RequestParam(required = false) String companyId) {
        return ApiResponse.ok(rankingService.supplierHome(period, companyId));
    }

    @GetMapping("/home/buyer")
    public ApiResponse<HomePayload> buyerHome(@RequestParam(defaultValue = "total") String period,
                                              @RequestParam(required = false) String companyId) {
        return ApiResponse.ok(rankingService.buyerHome(period, companyId));
    }

    @GetMapping("/rankings/sales")
    public ApiResponse<List<RankingItem>> salesRanking(@RequestParam(defaultValue = "total") String period,
                                                       @RequestParam(required = false) String companyId) {
        return ApiResponse.ok(rankingService.salesRanking(period, companyId));
    }

    @GetMapping("/rankings/purchase")
    public ApiResponse<List<RankingItem>> purchaseRanking(@RequestParam(defaultValue = "total") String period,
                                                          @RequestParam(required = false) String companyId) {
        return ApiResponse.ok(rankingService.purchaseRanking(period, companyId));
    }
}
