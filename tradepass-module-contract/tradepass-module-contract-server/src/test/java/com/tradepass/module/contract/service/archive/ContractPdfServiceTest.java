package com.tradepass.module.contract.service.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContractPdfServiceTest {

    @Test
    void contractNameOverridesLegacySnapshotTitle() throws Exception {
        ContractPdfService service = new ContractPdfServiceImpl(
                new ObjectMapper(), mock(CompanyReader.class));
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
                new ObjectMapper(), mock(CompanyReader.class));
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
}
