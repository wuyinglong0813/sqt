package com.tradepass.module.trade.controller.app.memo;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.module.trade.service.memo.PersonalMemoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PersonalMemoController {
    private final PersonalMemoService memoService;

    public PersonalMemoController(PersonalMemoService memoService) { this.memoService = memoService; }

    @GetMapping("/contracts/{id}/memo")
    public ApiResponse<Map<String, Object>> contractMemo(@PathVariable Long id) {
        return ApiResponse.ok(memoService.get(PersonalMemoService.CONTRACT, id));
    }

    @PostMapping("/contracts/{id}/memo")
    public ApiResponse<Map<String, Object>> saveContractMemo(@PathVariable Long id,
                                                             @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(memoService.save(PersonalMemoService.CONTRACT, id,
                String.valueOf(body.getOrDefault("content", ""))));
    }

    @GetMapping("/trade-documents/{id}/memo")
    public ApiResponse<Map<String, Object>> salesOrderMemo(@PathVariable Long id) {
        return ApiResponse.ok(memoService.get(PersonalMemoService.SALES_ORDER, id));
    }

    @PostMapping("/trade-documents/{id}/memo")
    public ApiResponse<Map<String, Object>> saveSalesOrderMemo(@PathVariable Long id,
                                                               @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(memoService.save(PersonalMemoService.SALES_ORDER, id,
                String.valueOf(body.getOrDefault("content", ""))));
    }
}
