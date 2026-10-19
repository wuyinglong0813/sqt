package com.tradepass.module.contract.service.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ContractResponseCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void legacySigningSnapshotsWithoutPresentationFieldsRemainReadable() throws Exception {
        var snapshot = json.readValue("""
                {"id":"12","contractNo":"HT12","companyId":"3","counterpartyCompanyId":"4",
                 "terms":"双方约定条款","status":"ACTIVE","versionNo":2,"amount":100}
                """, ContractRespDTO.class);
        assertThat(snapshot.id()).isEqualTo("12");
        assertThat(snapshot.versionNo()).isEqualTo(2);
        assertThat(snapshot.terms()).isEqualTo("双方约定条款");
        assertThat(snapshot.statusText()).isNull();
        assertThat(snapshot.abolishPending()).isFalse();
    }

    @Test
    void presentationProgressSurvivesJsonRoundTripWithoutChangingTheLegalState() throws Exception {
        var original = contract();
        var progress = original.withAbolishProgress("作废签署中");
        var decoded = json.readValue(json.writeValueAsBytes(progress), ContractRespDTO.class);
        assertThat(original.abolishPending()).isFalse();
        assertThat(decoded.abolishPending()).isTrue();
        assertThat(decoded.statusText()).isEqualTo("作废签署中");
        assertThat(decoded.status()).isEqualTo("ACTIVE");
        assertThat(decoded.terms()).isEqualTo(original.terms());
        assertThat(decoded.withStatusText("显示文案已更新").abolishPending()).isTrue();
        assertThat(decoded.withAbolishProgress(null).abolishPending()).isFalse();
    }

    @Test
    void priorStatusTextConstructorAndPayloadsDoNotInferAbolishStateFromDisplayText() throws Exception {
        var contract = new ContractRespDTO("12", "HT12", "3", "4", "需方", "SALE", "合同", "模板",
                BigDecimal.TEN, null, null, "条款", "ACTIVE", 1, "7", null, null, null,
                "供方", "需方", "3", "4", "需方", "SALE", "OUTGOING",
                BigDecimal.ZERO, BigDecimal.ZERO, 0, "履约中");
        assertThat(contract.abolishPending()).isFalse();
        var priorJson = json.valueToTree(contract);
        ((com.fasterxml.jackson.databind.node.ObjectNode) priorJson).remove("abolishPending");
        assertThat(json.treeToValue(priorJson, ContractRespDTO.class).abolishPending()).isFalse();
        assertThat(contract.withStatusText("作废签署中").abolishPending()).isFalse();
    }

    private ContractRespDTO contract() {
        return new ContractRespDTO("12", "HT12", "3", "4", "需方", "SALE", "合同", "模板",
                BigDecimal.TEN, null, null, "条款", "ACTIVE", 1, "7", null, null, null,
                "供方", "需方", "3", "4", "需方", "SALE", "OUTGOING");
    }
}
