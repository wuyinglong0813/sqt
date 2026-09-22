package com.tradepass.integration;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.framework.web.core.controller.ProbeController;

import com.tradepass.module.contract.controller.app.contract.TradeController;
import com.tradepass.module.trade.controller.app.order.OrderController;
import com.tradepass.module.trade.service.order.OrderService;
import com.tradepass.module.file.controller.app.file.FileController;
import com.tradepass.module.identity.controller.app.auth.AuthController;
import com.tradepass.module.identity.controller.app.company.CompanyController;
import com.tradepass.module.trade.controller.app.ranking.RankingController;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.web.core.handler.GlobalExceptionHandler;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanyProfile;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanySearchSummary;
import com.tradepass.framework.common.pojo.TradePassDtos.LoginSession;
import com.tradepass.framework.common.pojo.TradePassDtos.UserProfile;
import com.tradepass.module.identity.framework.config.AuthInterceptor;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.framework.common.pojo.PagePayload;
import com.tradepass.module.trade.api.order.dto.TradeOrderRespDTO;
import com.tradepass.module.identity.service.auth.AuthSessionService;
import com.tradepass.module.identity.service.auth.AuthService;
import com.tradepass.module.identity.service.company.CompanyService;
import com.tradepass.module.trade.service.ranking.RankingService;
import com.tradepass.module.contract.service.contract.TradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.time.LocalDate;
import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HttpApiContractTest {
    private AuthService authService;
    private CompanyService companyService;
    private TradeService tradeService;
    private OrderService orderService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        companyService = mock(CompanyService.class);
        tradeService = mock(TradeService.class);
        orderService = mock(OrderService.class);
        RankingService rankingService = mock(RankingService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService),
                        new CompanyController(companyService, null),
                        new TradeController(tradeService, null, null),
                        new OrderController(orderService),
                        new RankingController(rankingService),
                        new FileController(true),
                        new ProbeController(),
                        new MissingResourceController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void cloudPlatformProbeReturnsOk() throws Exception {
        mvc.perform(get("/tcb_probe"))
                .andExpect(status().isOk());
    }

    @Test
    void missingStaticResourceReturns404InsteadOf500() throws Exception {
        mvc.perform(get("/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Not Found"));
    }

    @Test
    void acceptsTrustedCloudIdentityWithoutLoginCode() throws Exception {
        UserProfile profile = new UserProfile("7", "cloud-openid", "", "用户", null, "GUEST");
        when(authService.wechatLogin(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("cloud-openid")))
                .thenReturn(new LoginSession("token", profile));

        mvc.perform(post("/api/auth/wechat-login")
                        .header("x-wx-openid", "cloud-openid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.openid").value("cloud-openid"));
    }

    @Test
    void wrapsSuccessfulLoginAndMinimalCompanySearch() throws Exception {
        UserProfile profile = new UserProfile("7", "openid", "", "用户", null, "GUEST");
        when(authService.wechatLogin(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new LoginSession("token", profile));
        mvc.perform(post("/api/auth/wechat-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("token"))
                .andExpect(jsonPath("$.data.user.currentRole").value("GUEST"));

        CompanySearchSummary company = new CompanySearchSummary(
                "3", "测试企业", "9113**********4567", true);
        when(companyService.searchCompanies("测试")).thenReturn(List.of(company));
        mvc.perform(get("/api/companies/search").param("keyword", "测试"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("测试企业"))
                .andExpect(jsonPath("$.data[0].maskedCreditCode").value("9113**********4567"))
                .andExpect(jsonPath("$.data[0].verified").value(true))
                .andExpect(jsonPath("$.data[0].legalPersonName").doesNotExist())
                .andExpect(jsonPath("$.data[0].contactPhone").doesNotExist())
                .andExpect(jsonPath("$.data[0].bankName").doesNotExist())
                .andExpect(jsonPath("$.data[0].bankAccount").doesNotExist());
    }

    @Test
    void companySearchRejectsMissingAuthenticationWhenInterceptorApplies() throws Exception {
        AuthSessionService sessionService = mock(AuthSessionService.class);
        CompanyMemberMapper memberMapper = mock(CompanyMemberMapper.class);
        when(sessionService.resolveUserId(null)).thenReturn(null);
        MockMvc protectedMvc = MockMvcBuilders.standaloneSetup(new CompanyController(companyService, null))
                .addInterceptors(new AuthInterceptor(sessionService, memberMapper))
                .build();

        protectedMvc.perform(get("/api/companies/search").param("keyword", "测试"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void convertsBusinessExceptionToStable400Response() throws Exception {
        when(tradeService.getTemplate(99L)).thenThrow(new BusinessException("模板不存在"));

        mvc.perform(get("/api/contract-templates/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("模板不存在"));
    }

    @Test
    void validatesAndBuildsUploadCredential() throws Exception {
        mvc.perform(post("/api/files/upload-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bizType\":\"seal\",\"fileName\":\"seal.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectKey").value(org.hamcrest.Matchers.startsWith("tradepass/seal/")))
                .andExpect(jsonPath("$.data.uploadUrl").value(org.hamcrest.Matchers.containsString("seal.png")));

        mvc.perform(post("/api/files/upload-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bizType\":\"\",\"fileName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new FileController(false).uploadToken(new FileController.UploadTokenRequest("seal", "seal.png")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件存储服务尚未配置");
    }

    @Test
    void returnsStablePaginationMetadata() throws Exception {
        TradeOrderRespDTO order = new TradeOrderRespDTO("8", "SALE", "客户",
                new BigDecimal("12.50"), LocalDate.of(2026, 7, 1), "CONFIRMED");
        when(orderService.pageOrders(null, null, 2, 10))
                .thenReturn(PagePayload.of(List.of(order), 21, 2, 10));

        mvc.perform(get("/api/orders").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("8"))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }

    @RestController
    private static class MissingResourceController {
        @GetMapping("/missing-resource")
        void missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "missing-resource");
        }
    }
}
