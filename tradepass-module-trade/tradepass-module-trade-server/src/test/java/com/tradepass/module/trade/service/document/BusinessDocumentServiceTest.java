package com.tradepass.module.trade.service.document;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.framework.audit.core.AuditLogService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentTemplateDO;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentTemplateMapper;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessDocumentServiceTest {
    private BusinessDocumentTemplateMapper templateMapper;
    private BusinessDocumentMapper documentMapper;
    private ContractReader contractMapper;
    private CompanyReader companyMapper;
    private BusinessDocumentService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(
                BusinessDocumentTemplateDO.class, BusinessDocumentDO.class,
                TradeContractRespDTO.class, CompanyRespDTO.class
        );
        templateMapper = mock(BusinessDocumentTemplateMapper.class);
        documentMapper = mock(BusinessDocumentMapper.class);
        contractMapper = mock(ContractReader.class);
        companyMapper = mock(CompanyReader.class);
        objectMapper = new ObjectMapper();
        service = new BusinessDocumentServiceImpl(
                templateMapper,
                documentMapper,
                contractMapper,
                companyMapper,
                mock(AccessControlOperations.class),
                mock(AuditLogService.class),
                objectMapper
        );
        AuthContext.set(7L, 3L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void createsDocumentFromSelectedTemplateAndEditedSnapshot() throws Exception {
        BusinessDocumentTemplateDO template = new BusinessDocumentTemplateDO();
        template.setId(11L);
        template.setCompanyId(3L);
        template.setDocumentType(BusinessDocumentService.SALES_ORDER);
        template.setName("标准销售单");
        template.setContent("{\"columns\":[\"序号\",\"品名\",\"数量\",\"金额\"],\"blankRows\":8}");
        when(templateMapper.selectOne(any())).thenReturn(template);

        TradeContractRespDTO contract = new TradeContractRespDTO();
        contract.setId(21L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        contract.setCounterpartyName("原往来单位");
        contract.setContractNo("HT-21");
        contract.setDirection("SALE");
        contract.setStatus("ACTIVE");
        contract.setAmount(new BigDecimal("99.00"));
        contract.setTerms("{\"sections\":[]}");
        when(contractMapper.selectById(21L)).thenReturn(contract);

        CompanyRespDTO company = new CompanyRespDTO();
        company.setId(3L);
        company.setName("原制单企业");
        when(companyMapper.selectById(3L)).thenReturn(company);

        AtomicReference<BusinessDocumentDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            BusinessDocumentDO document = invocation.getArgument(0);
            document.setId(31L);
            inserted.set(document);
            return 1;
        }).when(documentMapper).insert(any(BusinessDocumentDO.class));
        when(documentMapper.selectById(31L)).thenAnswer(invocation -> inserted.get());

        Map<String, Object> editedContent = Map.of(
                "title", "七月销售单",
                "companyName", "河北测试公司",
                "counterpartyName", "北京客户公司",
                "contractNo", "HT-EDITED",
                "date", "2026-07-22",
                "columns", List.of("序号", "品名", "数量", "金额"),
                "rows", List.of(List.of("1", "测试商品", "2", "88.50")),
                "blankRows", 8,
                "totalAmount", "88.5"
        );

        Map<String, Object> result = service.createDocument(21L, Map.of(
                "documentType", BusinessDocumentService.SALES_ORDER,
                "templateId", 11L,
                "content", editedContent
        ));

        assertThat(result).containsEntry("id", 31L).containsEntry("templateName", "标准销售单");
        BusinessDocumentDO document = inserted.get();
        assertThat(document.getTemplateId()).isEqualTo(11L);
        assertThat(document.getRecipientCompanyId()).isEqualTo(4L);
        assertThat(document.getStatus()).isEqualTo("DRAFT");
        assertThat(document.getSourceType()).isEqualTo("TEMPLATE");
        JsonNode content = objectMapper.readTree(document.getContent());
        assertThat(content.path("title").asText()).isEqualTo("七月销售单");
        assertThat(content.path("companyName").asText()).isEqualTo("河北测试公司");
        assertThat(content.path("counterpartyName").asText()).isEqualTo("北京客户公司");
        assertThat(content.path("rows").get(0).get(1).asText()).isEqualTo("测试商品");
        assertThat(content.path("totalAmount").asText()).isEqualTo("88.5");
        assertThat(content.path("templateName").asText()).isEqualTo("标准销售单");
        assertThat(content.path("preparedByName").asText()).isEqualTo("用户7");
    }

    @Test
    void pendingContractCreatesSupplierOnlyDraft() {
        BusinessDocumentTemplateDO template = new BusinessDocumentTemplateDO();
        template.setId(11L);
        template.setCompanyId(3L);
        template.setDocumentType(BusinessDocumentService.SALES_ORDER);
        template.setName("标准销售单");
        template.setContent("{\"columns\":[\"序号\",\"品名\",\"数量\",\"金额\"],\"blankRows\":8}");
        when(templateMapper.selectOne(any())).thenReturn(template);

        TradeContractRespDTO contract = new TradeContractRespDTO();
        contract.setId(22L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        contract.setCounterpartyName("采购企业");
        contract.setDirection("SALE");
        contract.setStatus("PENDING");
        contract.setAmount(new BigDecimal("20.00"));
        contract.setTerms("{\"sections\":[{\"type\":\"table\",\"columns\":[\"品名\",\"数量\",\"金额\"],\"rows\":[[\"商品A\",\"2\",\"20\"]]}]}");
        when(contractMapper.selectById(22L)).thenReturn(contract);

        CompanyRespDTO company = new CompanyRespDTO();
        company.setId(3L);
        company.setName("供应企业");
        when(companyMapper.selectById(3L)).thenReturn(company);

        AtomicReference<BusinessDocumentDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            BusinessDocumentDO document = invocation.getArgument(0);
            document.setId(32L);
            inserted.set(document);
            return 1;
        }).when(documentMapper).insert(any(BusinessDocumentDO.class));
        when(documentMapper.selectById(32L)).thenAnswer(invocation -> inserted.get());

        Map<String, Object> result = service.createDocument(22L, Map.of(
                "documentType", BusinessDocumentService.SALES_ORDER,
                "templateId", 11L
        ));

        assertThat(inserted.get().getStatus()).isEqualTo("DRAFT");
        assertThat(result).containsEntry("statusText", "草稿")
                .containsEntry("canEditDraft", true)
                .containsEntry("canPublish", false);
    }

    @Test
    void supplierCanEditAndPublishDraftAfterContractBecomesActive() throws Exception {
        TradeContractRespDTO contract = new TradeContractRespDTO();
        contract.setId(23L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        contract.setDirection("SALE");
        contract.setStatus("ACTIVE");
        when(contractMapper.selectById(23L)).thenReturn(contract);

        BusinessDocumentDO draft = new BusinessDocumentDO();
        draft.setId(33L);
        draft.setCompanyId(3L);
        draft.setRecipientCompanyId(4L);
        draft.setContractId(23L);
        draft.setDocumentType(BusinessDocumentService.SALES_ORDER);
        draft.setStatus("REJECTED");
        draft.setRejectedReason("数量有误");
        draft.setDocumentNo("XS-DRAFT-33");
        draft.setTemplateId(11L);
        draft.setTemplateName("标准销售单");
        draft.setContent("{\"title\":\"销售单\",\"columns\":[\"品名\",\"数量\",\"金额\"],\"rows\":[[\"商品A\",\"1\",\"10\"]],\"blankRows\":8}");
        when(documentMapper.selectById(33L)).thenReturn(draft);
        when(documentMapper.selectByIdForUpdate(33L)).thenReturn(draft);

        Map<String, Object> edited = service.updateDraft(33L, Map.of("content", Map.of(
                "title", "已修改草稿",
                "columns", List.of("品名", "数量", "金额"),
                "rows", List.of(List.of("商品A", "2", "20")),
                "blankRows", 8,
                "totalAmount", "20"
        )));
        assertThat(edited).containsEntry("canPublish", true);
        assertThat(objectMapper.readTree(draft.getContent()).path("title").asText()).isEqualTo("已修改草稿");
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getRejectedReason()).isNull();

        Map<String, Object> published = service.publishDraft(33L);
        assertThat(draft.getStatus()).isEqualTo("ISSUED");
        assertThat(published).containsEntry("statusText", "待对方确认")
                .containsEntry("canPublish", false);
    }

    @Test
    void buyerCreatesReturnOrderForSupplier() throws Exception {
        AuthContext.set(8L, 4L);
        BusinessDocumentTemplateDO template = new BusinessDocumentTemplateDO();
        template.setId(15L);
        template.setCompanyId(4L);
        template.setDocumentType(BusinessDocumentService.RETURN_ORDER);
        template.setName("标准退货单");
        template.setContent("{\"columns\":[\"序号\",\"品名\",\"数量\",\"金额\"],\"blankRows\":8}");
        when(templateMapper.selectOne(any())).thenReturn(template);

        TradeContractRespDTO contract = new TradeContractRespDTO();
        contract.setId(25L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        contract.setCounterpartyName("采购企业");
        contract.setDirection("SALE");
        contract.setStatus("ACTIVE");
        contract.setTerms("{\"sections\":[{\"type\":\"table\",\"columns\":[\"品名\",\"数量\",\"金额\"],\"rows\":[[\"电线\",\"2\",\"1000\"]]}]}");
        when(contractMapper.selectById(25L)).thenReturn(contract);

        CompanyRespDTO buyer = new CompanyRespDTO();
        buyer.setId(4L);
        buyer.setName("采购企业");
        CompanyRespDTO supplier = new CompanyRespDTO();
        supplier.setId(3L);
        supplier.setName("供应企业");
        when(companyMapper.selectById(4L)).thenReturn(buyer);
        when(companyMapper.selectById(3L)).thenReturn(supplier);

        AtomicReference<BusinessDocumentDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            BusinessDocumentDO document = invocation.getArgument(0);
            document.setId(35L);
            inserted.set(document);
            return 1;
        }).when(documentMapper).insert(any(BusinessDocumentDO.class));
        when(documentMapper.selectById(35L)).thenAnswer(invocation -> inserted.get());

        service.createDocument(25L, Map.of(
                "documentType", BusinessDocumentService.RETURN_ORDER,
                "templateId", 15L));

        assertThat(inserted.get().getCompanyId()).isEqualTo(4L);
        assertThat(inserted.get().getRecipientCompanyId()).isEqualTo(3L);
        assertThat(inserted.get().getDocumentNo()).startsWith("TH-");
        assertThat(objectMapper.readTree(inserted.get().getContent()).path("counterpartyName").asText())
                .isEqualTo("供应企业");
    }

    @Test
    void supplierCanAlsoCreateReturnOrderForBuyerConfirmation() {
        BusinessDocumentTemplateDO template = new BusinessDocumentTemplateDO();
        template.setId(16L);
        template.setCompanyId(3L);
        template.setDocumentType(BusinessDocumentService.RETURN_ORDER);
        template.setName("供方退货单");
        template.setContent("{\"columns\":[\"序号\",\"品名\",\"数量\",\"金额\"],\"blankRows\":8}");
        when(templateMapper.selectOne(any())).thenReturn(template);

        TradeContractRespDTO contract = new TradeContractRespDTO();
        contract.setId(26L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        contract.setCounterpartyName("采购企业");
        contract.setDirection("SALE");
        contract.setStatus("ACTIVE");
        contract.setTerms("{\"sections\":[{\"type\":\"table\",\"columns\":[\"品名\",\"数量\",\"金额\"],\"rows\":[[\"电线\",\"2\",\"1000\"]]}]}");
        when(contractMapper.selectById(26L)).thenReturn(contract);

        CompanyRespDTO supplier = new CompanyRespDTO();
        supplier.setId(3L);
        supplier.setName("供应企业");
        CompanyRespDTO buyer = new CompanyRespDTO();
        buyer.setId(4L);
        buyer.setName("采购企业");
        when(companyMapper.selectById(3L)).thenReturn(supplier);
        when(companyMapper.selectById(4L)).thenReturn(buyer);

        AtomicReference<BusinessDocumentDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            BusinessDocumentDO document = invocation.getArgument(0);
            document.setId(36L);
            inserted.set(document);
            return 1;
        }).when(documentMapper).insert(any(BusinessDocumentDO.class));
        when(documentMapper.selectById(36L)).thenAnswer(invocation -> inserted.get());

        service.createDocument(26L, Map.of(
                "documentType", BusinessDocumentService.RETURN_ORDER,
                "templateId", 16L));

        assertThat(inserted.get().getCompanyId()).isEqualTo(3L);
        assertThat(inserted.get().getRecipientCompanyId()).isEqualTo(4L);
        assertThat(inserted.get().getSupplierCompanyId()).isEqualTo(3L);
        assertThat(inserted.get().getBuyerCompanyId()).isEqualTo(4L);
    }

    @Test
    void ownerCanSoftDeleteUnpublishedDraft() {
        BusinessDocumentDO draft = new BusinessDocumentDO();
        draft.setId(37L);
        draft.setCompanyId(3L);
        draft.setDocumentType(BusinessDocumentService.SALES_ORDER);
        draft.setDocumentNo("XS-DRAFT-37");
        draft.setStatus("DRAFT");
        when(documentMapper.selectOne(any())).thenReturn(draft);

        assertThat(service.deleteDraft(37L)).isEqualTo("销售单草稿已删除");
        assertThat(draft.getDeletedBy()).isEqualTo(7L);
        assertThat(draft.getDeletedAt()).isNotNull();
    }
}
