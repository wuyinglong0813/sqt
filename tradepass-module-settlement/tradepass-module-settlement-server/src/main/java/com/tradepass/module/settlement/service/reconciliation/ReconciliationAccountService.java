package com.tradepass.module.settlement.service.reconciliation;

import com.tradepass.module.contract.api.directory.ContractDirectoryOperations;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations.*;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations.*;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.mybatis.core.ApplicationIds;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.identity.api.counterparty.CounterpartyReader;
import com.tradepass.module.identity.api.counterparty.CounterpartyReader.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationEntryDO;
import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationEntryMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface ReconciliationAccountService {
    public static final String SALES_ORDER = "SALES_ORDER";
    public static final String RETURN_ORDER = "RETURN_ORDER";
    public static final String PAYMENT_VOUCHER = "PAYMENT_VOUCHER";
    public static final String INVOICE = "INVOICE";

    public record ContractAccount(Long id, String contractNo, BigDecimal amount, LocalDate date) {
        }

    public record WorkbookEntry(String sourceType, LocalDate date, BigDecimal amount) {
        }

    List<Map<String, Object>> listAccounts();
    List<Map<String, Object>> listAccounts(String role);
    Map<String, Object> account(Long counterpartyCompanyId);
    Map<String, Object> account(Long counterpartyCompanyId, String role);
    WorkbookRespDTO workbook(Long counterpartyCompanyId);
    void recordSalesOrder(BusinessDocumentRespDTO document, BigDecimal amount, LocalDate businessDate, long approvedBy, LocalDateTime approvedAt);
    void recordReturnOrder(BusinessDocumentRespDTO document, BigDecimal amount, LocalDate businessDate, long approvedBy, LocalDateTime approvedAt);
    void recordAttachment(TradeContractRespDTO contract, String sourceType, long sourceId, LocalDate businessDate, String documentNo, BigDecimal amount, long issuerCompanyId, long approvedBy, LocalDateTime approvedAt);
    void reverseSource(String sourceType, long sourceId, long actionRequestId, long approvedBy, LocalDateTime approvedAt);
    byte[] generateWorkbook(String title, List<ContractAccount> contracts, Map<Long, List<WorkbookEntry>> entriesByContract);
}
