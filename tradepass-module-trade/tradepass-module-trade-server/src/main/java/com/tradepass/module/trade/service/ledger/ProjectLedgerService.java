package com.tradepass.module.trade.service.ledger;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations.*;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.mybatis.core.ApplicationIds;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public interface ProjectLedgerService {
    List<Map<String, Object>> listProjects();
    Map<String, Object> project(Long projectId);
    Map<String, Object> contractAssignment(Long contractId);
    Map<String, Object> dismissContractPrompt(Long contractId);
    Map<String, Object> createProject(String projectNo, String name, String description);
    List<Map<String, Object>> availableContracts(Long projectId);
    Map<String, Object> assignContracts(Long projectId, List<Long> contractIds);
    Map<String, Object> removeContract(Long projectId, Long contractId);
}
