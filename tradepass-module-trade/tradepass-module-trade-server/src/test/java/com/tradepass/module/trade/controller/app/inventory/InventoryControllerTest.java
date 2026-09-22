package com.tradepass.module.trade.controller.app.inventory;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryControllerTest {
    @Test
    void jsonSignatureUsesExistingReceiveValidation() {
        SalesOrderInventoryService service = mock(SalesOrderInventoryService.class);
        InventoryController controller = new InventoryController(service);
        controller.confirmDocument(17L, Map.of("decision", "APPROVE", "signatureBase64", "AQID"));
        verify(service).receive(eq(17L), eq("APPROVE"), isNull(), eq(""), eq("signature.png"), aryEqBytes());
    }
    private byte[] aryEqBytes() { return org.mockito.AdditionalMatchers.aryEq(new byte[]{1,2,3}); }
    @Test
    void rejectsInvalidOrOversizedEncoding() {
        assertThat(InventoryController.decodeSignature(null)).isNull();
        for (Object value : new Object[]{"", "%%%", 12, "A".repeat(700001)}) {
            assertThatThrownBy(() -> InventoryController.decodeSignature(value)).isInstanceOf(BusinessException.class);
        }
    }
}
