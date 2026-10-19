package com.tradepass.module.contract.service.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ContractPdfServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SALE", "PURCHASE"})
    void actualSignatureBoxesProvideFinalPageCoordinatesDespiteRepeatedKeywordsInLongClauses(String direction) throws Exception {
        var mapper = new ObjectMapper();
        var sections = mapper.createObjectNode();
        var clauses = sections.putArray("sections");
        for (int i = 1; i <= 70; i++) clauses.addObject().put("type", "clause").put("title", "条款" + i)
                .put("content", "需方盖章后按照约定付款。供方盖章并交货。正文还可重复包含供方盖章、需方盖章，不应影响签章框定位。");
        var contract = new ContractRespDTO("12", "HT-COORDINATES", "1", "2", "对方企业", direction,
                "多页签章定位", "自定义模板", BigDecimal.TEN, "2026-10-19", null, mapper.writeValueAsString(sections),
                "PENDING", 3, "1", null, null, "2026-10-19T12:00:00",
                "本方企业", "对方企业", "1", "2", "对方企业", direction, "OUTGOING");
        var service = new ContractPdfServiceImpl(mapper, id -> null);
        var signed = service.generateForSigning(contract);
        var reader = new PdfReader(signed.pdf());
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThan(1);
            assertThat(signed.supplierPosition().pageNo()).isEqualTo(reader.getNumberOfPages());
            assertThat(signed.buyerPosition().pageNo()).isEqualTo(reader.getNumberOfPages());
            String last = new PdfTextExtractor(reader).getTextFromPage(reader.getNumberOfPages());
            assertThat(last).contains("供方盖章", "需方盖章");
            double width = reader.getPageSize(reader.getNumberOfPages()).getWidth() * 4d / 3;
            double height = reader.getPageSize(reader.getNumberOfPages()).getHeight() * 4d / 3;
            assertThat(Double.parseDouble(signed.supplierPosition().x())).isBetween(0d, width / 2);
            assertThat(Double.parseDouble(signed.buyerPosition().x())).isBetween(width / 2, width);
            assertThat(Double.parseDouble(signed.supplierPosition().y())).isBetween(0d, height);
            assertThat(signed.supplierPosition().y()).isEqualTo(signed.buyerPosition().y());
            assertThat(new PdfTextExtractor(reader).getTextFromPage(1)).contains("需方盖章后", "供方盖章并交货");
        } finally { reader.close(); }
    }


    @Test
    void contractNameOverridesLegacySnapshotTitle() throws Exception {
        ContractPdfService service = new ContractPdfServiceImpl(
                new ObjectMapper(), id -> null);
        ContractRespDTO contract = new ContractRespDTO(
                "1", "HT-TEST", "1", "2", "对方企业", "SALE",
                "测试123", "标准模板", new BigDecimal("100"),
                "2026-07-22", null, "{\"title\":\"购销合同\"}",
                "ACTIVE", 1, "1", null, null, "2026-07-22T12:00:00",
                "本方企业", "对方企业",
                "1", "2", "对方企业", "SALE", "OUTGOING");

        PdfReader reader = new PdfReader(service.generate(contract));
        try {
            assertThat(reader.getInfo()).containsEntry("Title", "测试123");
        } finally {
            reader.close();
        }
    }

    @Test
    void rendersContractFeesSeparatelyFromProducts() throws Exception {
        ContractPdfService service = new ContractPdfServiceImpl(
                new ObjectMapper(), id -> null);
        String terms = """
                {"title":"购销合同","sections":[
                  {"type":"table","columns":["产品名称","规格型号","单位","数量","单价(元)","金额(元)","备注"],
                   "rows":[["电线","A-1","米","2","50","100",""]],
                   "summary":{"totalAmount":"100","totalAmountCn":"壹佰元整"}},
                  {"type":"fees","items":[{"feeType":"运费","amount":"15","remark":"送货上门"}]}
                ]}
                """;
        ContractRespDTO contract = new ContractRespDTO(
                "2", "HT-FEE", "1", "2", "对方企业", "SALE",
                "含费用合同", "标准模板", new BigDecimal("115"),
                "2026-08-23", null, terms,
                "ACTIVE", 1, "1", null, null, "2026-08-23T12:00:00",
                "本方企业", "对方企业",
                "1", "2", "对方企业", "SALE", "OUTGOING");

        PdfReader reader = new PdfReader(service.generate(contract));
        try {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("其他费用", "运费", "送货上门", "合同总额：115");
        } finally {
            reader.close();
        }
    }

    @Test
    void rendersOnlyAgreedTemplateClausesInTheirOriginalOrderStartingAtOne() throws Exception {
        String terms = """
                {"sections":[
                  {"type":"table","title":"一、订购货品","columns":["名称","规格型号","单位","数量","单价","金额","备注"],
                   "rows":[["电线","NH-RVS","盘","200","280","56000",""]]},
                  {"type":"clause","title":"2、质量要求","content":"产品质量执行双方约定标准"},
                  {"type":"clause","title":"三、交货","content":"运输费用由供方承担"},
                  {"type":"clause","title":"第四条 验收","content":"收货七日内提出异议"},
                  {"type":"clause","title":"结算方式","content":"付清全款发货"},
                  {"type":"clause","title":"违约责任","content":"按双方约定执行"},
                  {"type":"clause","title":"争议解决","content":"向供方所在地人民法院起诉"},
                  {"type":"clause","title":"其他约定","content":"双方签字盖章之日生效"}
                ]}
                """;

        String text = pdfText(terms);

        assertThat(text).contains("订购货品", "一、质量要求", "二、交货", "三、验收", "四、结算方式",
                "五、违约责任", "六、争议解决", "七、其他约定");
        assertThat(text).doesNotContain("一、订购货品", "二、2、", "质量要求、技术标准", "包装标准及费用",
                "合同生效与变更", "（未约定）", "八、", "十一、");
        assertThat(text.indexOf("产品质量执行双方约定标准"))
                .isLessThan(text.indexOf("运输费用由供方承担"));
        assertThat(text.indexOf("付清全款发货"))
                .isLessThan(text.indexOf("按双方约定执行"));
        assertThat(text.split("产品质量执行双方约定标准", -1)).hasSize(2);
        assertThat(text.split("按双方约定执行", -1)).hasSize(2);
    }

    @Test
    void emptyTemplateDoesNotCreateAdditionalContractTerms() throws Exception {
        assertThat(pdfText("{\"sections\":[]}"))
                .doesNotContain("（未约定）", "质量要求", "违约责任", "合同生效与变更", "一、", "二、");
    }

    @Test
    void preservesLegacyPlainTextTermsWithoutAddingStandardTerms() throws Exception {
        String text = pdfText("双方约定：付清全款发货。");
        assertThat(text).contains("一、合同条款", "双方约定：付清全款发货。");
        assertThat(text).doesNotContain("二、", "（未约定）", "质量要求", "合同生效与变更");
    }

    @Test
    void keepsAnIntentionallyEmptyTemplateClauseWithoutAddingOtherClauses() throws Exception {
        String text = pdfText("{\"sections\":[{\"type\":\"clause\",\"title\":\"定制条款\",\"content\":\"\"}]}");
        assertThat(text).contains("一、定制条款：", "（未约定）");
        assertThat(text).doesNotContain("二、", "质量要求", "合同生效与变更");
    }

    private String pdfText(String terms) throws Exception {
        ContractPdfService service = new ContractPdfServiceImpl(
                new ObjectMapper(), id -> null);
        ContractRespDTO contract = new ContractRespDTO(
                "3", "HT-TEMPLATE", "1", "2", "对方企业", "SALE",
                "购销合同", "企业自定义模板", new BigDecimal("56000"),
                "2026-10-19", null, terms,
                "PENDING", 1, "1", null, null, "2026-10-19T12:00:00",
                "本方企业", "对方企业", "1", "2", "对方企业", "SALE", "OUTGOING");
        PdfReader reader = new PdfReader(service.generate(contract));
        try {
            StringBuilder text = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }
}
