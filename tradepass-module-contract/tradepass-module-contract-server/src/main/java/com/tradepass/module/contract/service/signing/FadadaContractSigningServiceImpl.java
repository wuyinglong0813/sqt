package com.tradepass.module.contract.service.signing;

import com.tradepass.module.contract.api.signing.FadadaContractSigningOperations;
import com.tradepass.module.contract.api.signing.FadadaContractSigningOperations.*;
import com.tradepass.module.contract.service.abolish.ContractAbolishIntentService;
import com.tradepass.module.contract.service.abolish.ContractAbolishRecoveryService;
import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.contract.TradeService;
import com.tradepass.module.contract.service.membership.MembershipService;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations.*;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations.*;
import com.tradepass.framework.cache.core.BoundedBinaryCache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import com.tradepass.module.contract.api.signing.dto.ContractSigningRespDTO;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.identity.api.fadada.dto.FadadaCorpIdentityRespDTO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

@Service
public class FadadaContractSigningServiceImpl implements FadadaContractSigningService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.trade.api.bilateral.BilateralStateOperations bilateralState;

    private final FadadaContractSignTaskMapper taskMapper;
    private final TradeContractMapper contractMapper;
    private final CompanyReader companyMapper;
    private final AccessControlOperations accessControl;
    private final FadadaCompanyOperations companyService;
    private final FadadaSigningGateway gateway;
    private final ContractPdfService pdfService;
    private final ContractArchiveService archiveService;
    private final TradeService tradeService;
    private final FadadaProperties properties;
    private final JdbcTemplate jdbc;
    private ContractAbolishIntentService abolishIntents;
    private MembershipService membership;

    @Autowired
    public void setMembershipService(MembershipService membership) { this.membership = membership; }

    public java.util.Map<String, Object> signingQuote(Long contractId) {
        long companyId = AuthContext.requireCompanyId();
        accessControl.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId);
        FadadaContractSignTaskDO task = find(contract);
        if (!"PENDING".equals(contract.getStatus())) throw new BusinessException("当前合同不在待签署状态");
        if (task != null && terminal(task.getProviderStatus())) {
            return java.util.Map.of("canSign", false, "newTask", false, "message",
                    "task_finished".equals(task.getProviderStatus()) && task.getArchivedAt() == null
                            ? "签署结果确认中，请稍后刷新" : statusText(task.getProviderStatus()));
        }
        if (!contract.getCompanyId().equals(companyId))
            return java.util.Map.of("canSign", true, "newTask", false, "message", "接收方签署免费，额度由发起方承担");
        if (task != null && hasText(task.getSignTaskId()))
            return java.util.Map.of("canSign", true, "newTask", false, "message", "继续已有签署，不再消耗额度");
        if (membership == null) return java.util.Map.of("canSign", true, "newTask", true, "message", "进入合同签署");
        MembershipService.Reservation existing = membership.existing(contractId, version(contract));
        if (existing != null && "CONSUMED".equals(existing.status()))
            return java.util.Map.of("canSign", true, "newTask", false, "message", "恢复已有签署，不再消耗额度");
        if (existing != null && !"RELEASED".equals(existing.status()))
            return java.util.Map.of("canSign", false, "newTask", false, "message", "上次签署创建结果待核实，请联系平台");
        var status = membership.status(requireCompany(companyId));
        return java.util.Map.of("canSign", status.canInitiate(), "newTask", true, "message", status.reason(),
                "policyRevision", status.policyRevision());
    }

    @Autowired
    public void setAbolishIntentService(ContractAbolishIntentService service) { this.abolishIntents = service; }
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BoundedBinaryCache signedPreviewCache =
            new BoundedBinaryCache(Duration.ofMinutes(30), 64L * 1024 * 1024);

    public FadadaContractSigningServiceImpl(FadadaContractSignTaskMapper taskMapper,
                                        TradeContractMapper contractMapper, CompanyReader companyMapper,
                                        AccessControlOperations accessControl, FadadaCompanyOperations companyService,
                                        FadadaSigningGateway gateway, ContractPdfService pdfService,
                                        ContractArchiveService archiveService, TradeService tradeService,
                                        FadadaProperties properties,
                                        JdbcTemplate jdbc) {
        this.taskMapper = taskMapper;
        this.contractMapper = contractMapper;
        this.companyMapper = companyMapper;
        this.accessControl = accessControl;
        this.companyService = companyService;
        this.gateway = gateway;
        this.pdfService = pdfService;
        this.archiveService = archiveService;
        this.tradeService = tradeService;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    public ContractSigningRespDTO current(Long contractId) {
        long companyId = AuthContext.requireCompanyId();
        accessControl.requireAnyPermission(companyId, "contract_view", "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId);
        return payload(contract, find(contract));
    }

    public ServiceUrlPayload memberSealGrantUrl(Long contractId, long userId) {
        requireReady();
        long companyId = AuthContext.requireCompanyId();
        accessControl.requireManager(companyId);
        accessControl.requirePermission(companyId, "seal_manage");
        TradeContractDO contract = requireParty(contractId, companyId);
        FadadaContractSignTaskDO task = find(contract);
        String sealId = null;
        if (task != null && hasText(task.getSignTaskId())) {
            String taskId = hasText(task.getAbolishedSignTaskId())
                    ? task.getAbolishedSignTaskId() : task.getSignTaskId();
            var sealIds = gateway.actorSealIds(taskId, actorId(task, companyId));
            if (sealIds == null || sealIds.size() != 1) {
                throw new BusinessException("暂无法确定本合同需授权的具体印章，请由印章管理员在法大大用印管理页面核对办理");
            }
            sealId = sealIds.get(0);
        }
        return companyService.memberSealGrantUrl(companyId, userId, sealId);
    }

    /** A delivered completion must remain retryable until its business effect is durable. */
    public void requireCallbackSettled(String signTaskId, String eventType) {
        if (!"sign-task-finished".equals(eventType) && !"sign-task-abolish".equals(eventType)) return;
        var task = taskMapper.selectOne(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .and(q -> q.eq(FadadaContractSignTaskDO::getSignTaskId, signTaskId)
                        .or().eq(FadadaContractSignTaskDO::getAbolishedSignTaskId, signTaskId)).last("LIMIT 1"));
        if (task == null) return; // retired child callbacks are settled by their own recovery path
        var contract = contractMapper.selectById(task.getContractId());
        if (contract == null || version(contract) != taskVersion(task)
                || !("PENDING".equals(contract.getStatus()) || "ACTIVE".equals(contract.getStatus()))) return;
        String observed = task.getProviderStatus();
        if (observed != null && observed.startsWith("ABOLISH_")) observed = observed.substring(8);
        if (ContractAbolishRecoveryService.stopped(observed)) return;
        boolean abolish = "sign-task-abolish".equals(eventType) || signTaskId.equals(task.getAbolishedSignTaskId());
        if (abolish ? !"revoked".equalsIgnoreCase(task.getProviderStatus()) : task.getArchivedAt() == null) {
            throw new BusinessException("签署完成通知已收到，结果尚未同步完整，将在后台继续核对");
        }
    }

    @Transactional
    public ServiceUrlPayload signUrl(Long contractId) {
        requireReady();
        long companyId = AuthContext.requireCompanyId();
        accessControl.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId, true);
        if (!"PENDING".equals(contract.getStatus())) throw new BusinessException("当前合同不在待签署状态");
        // Local contract permissions do not establish membership of the provider enterprise.
        // Check before preparing a billable task or issuing a personal signing link.
        companyService.requireCurrentSigningMember(companyId);
        FadadaContractSignTaskDO task = find(contract, true);
        if (contract.getCompanyId().equals(companyId) && (task == null || !hasText(task.getSignTaskId()))) {
            // A new task uses the currently configured seal. Existing signing and
            // abolishment tasks retain their original provider seal configuration.
            companyService.requireCurrentSigningAuthorization(companyId);
            task = prepare(contract);
        }
        if (task == null || !hasText(task.getSignTaskId())) {
            throw new BusinessException("请等待合同发起方先完成签署");
        }
        task = sync(task, contract);
        String actorId = actorId(task, companyId);
        if (isSigned(actorStatus(task, companyId))) throw new BusinessException("当前企业已完成签署，请等待对方签署");
        if (terminal(task.getProviderStatus())) throw new BusinessException("签署任务已结束，请刷新合同状态");
        if (!contract.getCompanyId().equals(companyId) && !isSigned(task.getInitiatorSignStatus())) {
            throw new BusinessException("请等待合同发起方先完成签署");
        }
        String url = gateway.actorUrl(task.getSignTaskId(), actorId,
                "tradepass-user-" + AuthContext.userId(),
                "/pages/service-return/service-return?scene=contract&contractId=" + contractId);
        validateUrl(url);
        return new ServiceUrlPayload(url, "contract", task.getProviderStatus());
    }

    @Transactional
    public ContractSigningRespDTO syncCurrent(Long contractId) {
        requireReady();
        long companyId = AuthContext.requireCompanyId();
        accessControl.requireAnyPermission(companyId, "contract_view", "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId, true);
        FadadaContractSignTaskDO task = find(contract, true);
        if (task == null || !hasText(task.getSignTaskId())) return payload(contract, task);
        task = sync(task, contract);
        return payload(contractMapper.selectByIdForUpdate(contractId), task);
    }

    public SignedPreview signedPreview(Long contractId) {
        requireReady();
        long companyId = AuthContext.requireCompanyId();
        accessControl.requireAnyPermission(companyId, "contract_view", "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId);
        FadadaContractSignTaskDO task = find(contract);
        if (task == null || !hasText(task.getSignTaskId()) || task.getArchivedAt() == null) {
            throw new BusinessException("合同真实签章页尚未归档");
        }
        FadadaCorpIdentityRespDTO owner = companyService.requireVerified(contract.getCompanyId());
        byte[] image = signedPreviewCache.get(
                "signed-preview:" + task.getSignTaskId(),
                () -> gateway.downloadSignedPreviewPage(task.getSignTaskId(), owner.getOpenCorpId()));
        return new SignedPreview("合同签章页.png", image);
    }

    @Transactional
    public void syncBySignTaskId(String signTaskId) {
        FadadaContractSignTaskDO task = taskMapper.selectOne(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .and(query -> query.eq(FadadaContractSignTaskDO::getSignTaskId, signTaskId)
                        .or().eq(FadadaContractSignTaskDO::getAbolishedSignTaskId, signTaskId))
                .last("LIMIT 1"));
        if (task == null) {
            if (membership != null) {
                Long recoverId = membership.confirmedContract(signTaskId);
                if (recoverId != null) {
                    TradeContractDO recover = contractMapper.selectByIdForUpdate(recoverId);
                    if (recover != null && "PENDING".equals(recover.getStatus())) {
                        MembershipService.Reservation receipt = membership.existing(recoverId, version(recover));
                        if (receipt != null && signTaskId.equals(receipt.signTaskId())) task = prepare(recover);
                    }
                }
            }
        }
        if (task == null) {
            if (syncCancelledAbolishTask(signTaskId, false)) return;
            if (recoverUnknownAbolishCallback(signTaskId)) return;
            throw new BusinessException("签署任务尚未就绪，请稍后重试");
        }
        TradeContractDO contract = contractMapper.selectByIdForUpdate(task.getContractId());
        if (contract == null || version(contract) != taskVersion(task)) return;
        // Locking read refreshes task state after another callback committed.
        task = taskMapper.selectOne(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .eq(FadadaContractSignTaskDO::getId, task.getId()).last("LIMIT 1 FOR UPDATE"));
        if (task != null && !signTaskId.equals(task.getSignTaskId())
                && !signTaskId.equals(task.getAbolishedSignTaskId())) {
            if (!syncCancelledAbolishTask(signTaskId, true)) throw new BusinessException("签署任务关联状态已变化，请稍后重试");
        } else if (task != null) {
            sync(task, contract);
        }
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ServiceUrlPayload abolishUrl(Long contractId, String reason) {
        requireReady();
        long companyId = AuthContext.requireCompanyId();
        accessControl.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId, true);
        if (!"ACTIVE".equals(contract.getStatus())) throw new BusinessException("仅履约中的合同可以发起作废签署");
        var approved = bilateralState != null ? bilateralState.approvedVoids(contractId, true) : jdbc.queryForList("""
                SELECT id FROM bilateral_action_request
                WHERE contract_id = ? AND biz_type = 'CONTRACT' AND action_type = 'VOID'
                  AND status = 'APPROVED' FOR UPDATE
                """, Long.class, contractId);
        if (approved.isEmpty()) {
            throw new BusinessException("请先由双方确认合同作废申请");
        }
        var recovering = bilateralState != null ? bilateralState.pendingResumes(contractId, false, true) : jdbc.queryForList("""
                SELECT id FROM bilateral_action_request
                WHERE contract_id = ? AND action_type = 'RESUME' AND status = 'PENDING'
                FOR UPDATE
                """, Long.class, contractId);
        if (!recovering.isEmpty()) throw new BusinessException("双方正在确认恢复履约，请先处理恢复申请");
        FadadaContractSignTaskDO task = find(contract, true);
        if (task == null || !hasText(task.getSignTaskId())) throw new BusinessException("该合同没有电子签署记录");
        String checkedReason = safeReason(reason);
        companyService.requireCurrentSigningMember(companyId);
        reconcileAbolishIntent(task);
        if (!hasText(task.getAbolishedSignTaskId()) || abolishRetryable(task.getProviderStatus())) {
            var original = gateway.status(task.getSignTaskId());
            requireRemoteTask(original, task.getSignTaskId());
            if ("revoked".equalsIgnoreCase(original.status())) {
                completeAbolish(task, contract);
                taskMapper.updateById(task);
                throw new BusinessException("合同已完成作废，请刷新合同状态");
            }
            if (hasText(task.getAbolishedSignTaskId())) {
                var oldStatus = gateway.status(task.getAbolishedSignTaskId());
                if (oldStatus == null || !ContractAbolishRecoveryService.stopped(oldStatus.status())) {
                    throw new BusinessException("上一作废任务尚未确认终止，请先同步状态");
                }
                jdbc.update("""
                        INSERT IGNORE INTO fadada_cancelled_abolish_task
                        (sign_task_id, contract_id, version_no, original_sign_task_id)
                        VALUES (?, ?, ?, ?)
                        """, task.getAbolishedSignTaskId(), contractId, version(contract), task.getSignTaskId());
            }
            if (abolishIntents == null) throw new BusinessException("作废任务安全记录服务不可用，请稍后重试");
            String intentId = abolishIntents.begin(task);
            String previousChildId = task.getAbolishedSignTaskId();
            try {
                String abolishId = gateway.abolish(task.getSignTaskId(), actorId(task, companyId), checkedReason, properties.getCallbackUrl());
                if (!hasText(abolishId) || abolishId.equals(previousChildId)) throw new BusinessException("未返回新的作废任务编号");
                task.setAbolishedSignTaskId(abolishId);
            } catch (RuntimeException exception) {
                task.setProviderStatus("ABOLISH_CREATION_UNCERTAIN");
                task.setLastError(shortMessage(exception));
                taskMapper.updateById(task);
                throw new BusinessException("作废任务创建结果尚未确认，合同保持只读，请核实电子签记录后处理");
            }
            task.setProviderStatus("abolishing");
            task.setInitiatorSignStatus(null);
            task.setCounterpartySignStatus(null);
            taskMapper.update(task, new LambdaUpdateWrapper<FadadaContractSignTaskDO>()
                    .eq(FadadaContractSignTaskDO::getId, task.getId())
                    .set(FadadaContractSignTaskDO::getInitiatorSignStatus, null)
                    .set(FadadaContractSignTaskDO::getCounterpartySignStatus, null));
            abolishIntents.confirm(intentId, task.getAbolishedSignTaskId());
        }
        String actorId = actorId(task, companyId);
        String url;
        try {
            url = gateway.actorUrl(task.getAbolishedSignTaskId(), actorId,
                    "tradepass-user-" + AuthContext.userId(),
                    "/pages/service-return/service-return?scene=abolish&contractId=" + contractId);
        } catch (RuntimeException exception) {
            // Keep the successfully created provider id even when loading its page fails.
            throw new BusinessException("作废任务已保留，签署页面暂时无法打开，请稍后重试");
        }
        validateUrl(url);
        return new ServiceUrlPayload(url, "abolish", task.getProviderStatus());
    }

    private FadadaContractSignTaskDO prepare(TradeContractDO contract) {
        FadadaContractSignTaskDO existing = find(contract, true);
        if (existing != null && hasText(existing.getSignTaskId())) return existing;
        MembershipService.Reservation receipt = membership == null ? null : membership.existing(contract.getId(), version(contract));
        if (receipt != null && "CONSUMED".equals(receipt.status())) {
            FadadaContractSignTaskDO restored = existing == null ? new FadadaContractSignTaskDO() : existing;
            restored.setContractId(contract.getId());
            restored.setVersionNo(version(contract));
            restored.setContractSnapshot(receipt.snapshot());
            ContractRespDTO frozen = signingSnapshot(contract, restored);
            restored.setInitiatorCompanyId(contract.getCompanyId());
            restored.setCounterpartyCompanyId(contract.getCounterpartyCompanyId());
            String initiatorActor = "SALE".equalsIgnoreCase(frozen.direction()) ? "supplier" : "buyer";
            restored.setInitiatorActorId(initiatorActor);
            restored.setCounterpartyActorId("supplier".equals(initiatorActor) ? "buyer" : "supplier");
            restored.setSignTaskId(receipt.signTaskId());
            restored.setSourceFileId(receipt.sourceFileId());
            restored.setDocId(receipt.docId());
            restored.setSourceSha256(receipt.sha256());
            restored.setProviderStatus("sign_progress");
            restored.setPreparedAt(LocalDateTime.now());
            restored.setLastError("");
            if (existing == null) taskMapper.insert(restored); else taskMapper.updateById(restored);
            return restored;
        }
        CompanyRespDTO initiator = requireCompany(contract.getCompanyId());
        CompanyRespDTO counterparty = requireCompany(contract.getCounterpartyCompanyId());
        FadadaCorpIdentityRespDTO initiatorIdentity = companyService.requireVerified(initiator.getId());
        FadadaCorpIdentityRespDTO counterpartyIdentity = companyService.requireVerified(counterparty.getId());
        String initiatorSeal = companyService.enabledSealId(initiator.getId());
        String counterpartySeal = companyService.enabledSealId(counterparty.getId());
        String initiatorActor = "SALE".equalsIgnoreCase(contract.getDirection()) ? "supplier" : "buyer";
        String counterpartyActor = "supplier".equals(initiatorActor) ? "buyer" : "supplier";
        ContractRespDTO payload = signingSnapshot(contract, existing);
        var signingDocument = pdfService.generateForSigning(payload);
        byte[] pdf = signingDocument.pdf();
        var createCommand = new FadadaSigningGateway.CreateTaskCommand(
                pdf, pdfService.fileName(payload), contract.getName(),
                "contract-" + contract.getId() + "-v" + version(contract),
                initiatorIdentity.getOpenCorpId(), initiatorActor, initiator.getName(), initiatorSeal,
                counterpartyIdentity.getOpenCorpId(), counterpartyActor, counterparty.getName(), counterpartySeal,
                "supplier", "buyer", properties.getCallbackUrl(),
                signingDocument.supplierPosition(), signingDocument.buyerPosition());
        createCommand.validate(); // Fail locally before reserving quota or creating a remote task.
        String sha256 = FileTypeInspector.sha256(pdf);
        FadadaContractSignTaskDO task = existing == null ? new FadadaContractSignTaskDO() : existing;
        task.setContractSnapshot(serializeSnapshot(payload));
        if (existing == null) {
            task.setContractId(contract.getId());
            task.setVersionNo(contract.getVersionNo() == null ? 1 : contract.getVersionNo());
            task.setInitiatorCompanyId(contract.getCompanyId());
            task.setCounterpartyCompanyId(contract.getCounterpartyCompanyId());
            task.setInitiatorActorId(initiatorActor);
            task.setCounterpartyActorId(counterpartyActor);
            task.setProviderStatus("CREATING");
            try {
                taskMapper.insert(task);
            } catch (DuplicateKeyException concurrent) {
                FadadaContractSignTaskDO winner = find(contract, true);
                if (winner != null && hasText(winner.getSignTaskId())) return winner;
                throw new BusinessException("签署文件正在准备，请稍后重试");
            }
        }
        MembershipService.Reservation reservation = membership == null ? null
                : membership.reserve(initiator, contract.getId(), version(contract), task.getContractSnapshot(), sha256);
        try {
            FadadaSigningGateway.CreatedTask created;
            if (reservation != null && !reservation.createAllowed()) {
                created = new FadadaSigningGateway.CreatedTask(
                        reservation.signTaskId(), reservation.sourceFileId(), reservation.docId());
                task.setContractSnapshot(reservation.snapshot());
                sha256 = reservation.sha256();
            } else {
                created = gateway.createTask(createCommand);
                if (reservation != null) membership.confirm(reservation.id(), created);
            }
            task.setSignTaskId(created.signTaskId());
            task.setSourceFileId(created.fileId());
            task.setDocId(created.docId());
            task.setSourceSha256(sha256);
            task.setProviderStatus("sign_progress");
            task.setLastError("");
            task.setPreparedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            return task;
        } catch (RuntimeException exception) {
            if (reservation != null) {
                if (exception instanceof com.tradepass.framework.fadada.core.FadadaTaskPreparationException && reservation.createAllowed()) {
                    membership.releaseBeforeTaskCreation(reservation.id());
                } else {
                    membership.uncertain(reservation.id());
                }
            }
            task.setProviderStatus("CREATE_FAILED");
            task.setLastError(shortMessage(exception));
            taskMapper.updateById(task);
            throw exception;
        }
    }

    private FadadaContractSignTaskDO sync(FadadaContractSignTaskDO task, TradeContractDO contract) {
        return sync(task, contract, true);
    }

    private FadadaContractSignTaskDO sync(FadadaContractSignTaskDO task, TradeContractDO contract, boolean reconcileIntent) {
        if (!contract.getId().equals(task.getContractId()) || version(contract) != taskVersion(task)) return task;
        if (!"PENDING".equals(contract.getStatus()) && !"ACTIVE".equals(contract.getStatus())) return task;
        String remoteTaskId = hasText(task.getAbolishedSignTaskId())
                ? task.getAbolishedSignTaskId() : task.getSignTaskId();
        FadadaSigningGateway.TaskStatus status = gateway.status(remoteTaskId);
        requireRemoteTask(status, remoteTaskId);
        // A parent callback can be the only event received for an externally completed
        // abolishment. Its revoked state is authoritative even if no child id was saved.
        if (!hasText(task.getAbolishedSignTaskId()) && "ACTIVE".equals(contract.getStatus())
                && "revoked".equalsIgnoreCase(status.status())) {
            completeAbolish(task, contract);
            taskMapper.updateById(task);
            return task;
        }
        if (reconcileIntent) {
            String previousChild = task.getAbolishedSignTaskId();
            reconcileAbolishIntent(task);
            if (!java.util.Objects.equals(previousChild, task.getAbolishedSignTaskId())) {
                status = gateway.status(task.getAbolishedSignTaskId());
                requireRemoteTask(status, task.getAbolishedSignTaskId());
            }
        }
        boolean discoveredChild = false;
        // Recover provider-created children on normal refresh as well as uncertain-create
        // recovery. Otherwise a completed child can be hidden behind a finished parent.
        if (!hasText(task.getAbolishedSignTaskId()) && "ACTIVE".equals(contract.getStatus())
                && hasText(status.abolishedSignTaskId())) {
            String childId = status.abolishedSignTaskId();
            var child = gateway.status(childId);
            requireRemoteTask(child, childId);
            requireAbolishParent(child, task.getSignTaskId());
            if (retiredAbolishTask(task, childId)) {
                // The provider can retain its last child pointer after bilateral recovery.
                // A confirmed stopped child must not re-lock a successfully resumed contract.
                if ("task_finished".equalsIgnoreCase(child.status()) || "revoked".equalsIgnoreCase(child.status())) {
                    completeAbolish(task, contract);
                    taskMapper.updateById(task);
                    return task;
                }
                if (!ContractAbolishRecoveryService.stopped(child.status())) {
                    throw new BusinessException("已取消作废任务的远程状态发生变化，需要核实处理");
                }
            } else {
                task.setAbolishedSignTaskId(childId);
                task.setInitiatorSignStatus(null);
                task.setCounterpartySignStatus(null);
                status = child;
                discoveredChild = true;
            }
        }
        if (hasText(task.getAbolishedSignTaskId())) requireAbolishParent(status, task.getSignTaskId());
        var currentActors = status.actors();
        for (FadadaSigningGateway.ActorStatus actor : currentActors) {
            if (task.getInitiatorActorId().equals(actor.actorId())) task.setInitiatorSignStatus(actor.signStatus());
            if (task.getCounterpartyActorId().equals(actor.actorId())) task.setCounterpartySignStatus(actor.signStatus());
        }
        if (hasText(task.getAbolishedSignTaskId())) {
            task.setProviderStatus("ABOLISH_" + status.status());
            if ("task_finished".equalsIgnoreCase(status.status()) || "revoked".equalsIgnoreCase(status.status())) {
                completeAbolish(task, contract);
            }
        } else {
            task.setProviderStatus(status.status());
            if ("task_finished".equalsIgnoreCase(status.status()) && task.getArchivedAt() == null) {
                boolean bothSigned = java.util.stream.Stream.of(task.getInitiatorActorId(), task.getCounterpartyActorId())
                        .allMatch(id -> currentActors.stream().anyMatch(actor -> id.equals(actor.actorId())
                                && "signed".equals(actor.signStatus())));
                if (!bothSigned) {
                    task.setLastError("签署任务已结束，但双方签署证据尚未齐全，请稍后同步或联系管理员核验");
                    taskMapper.updateById(task);
                    return task;
                }
                FadadaCorpIdentityRespDTO owner = companyService.requireVerified(contract.getCompanyId());
                ContractRespDTO payload = signingSnapshot(contract, task);
                task.setContractSnapshot(serializeSnapshot(payload));
                byte[] signed = gateway.downloadSignedPdf(task.getSignTaskId(), owner.getOpenCorpId(),
                        pdfService.fileName(payload));
                archiveService.archiveSignedPdf(payload, signed, task.getSignTaskId(), safeUserId(contract));
                task.setArchivedAt(LocalDateTime.now());
                task.setFinishedAt(LocalDateTime.now());
                tradeService.activateAfterElectronicSignature(contract.getId(), taskVersion(task), safeUserId(contract));
            }
        }
        task.setLastError("");
        if (discoveredChild) {
            taskMapper.update(task, new LambdaUpdateWrapper<FadadaContractSignTaskDO>()
                    .eq(FadadaContractSignTaskDO::getId, task.getId())
                    .set(FadadaContractSignTaskDO::getInitiatorSignStatus, task.getInitiatorSignStatus())
                    .set(FadadaContractSignTaskDO::getCounterpartySignStatus, task.getCounterpartySignStatus()));
        } else {
            taskMapper.updateById(task);
        }
        return task;
    }

    private void requireRemoteTask(FadadaSigningGateway.TaskStatus status, String expectedId) {
        if (status == null || !expectedId.equals(status.signTaskId()) || !hasText(status.status())) {
            throw new BusinessException("无法确认电子签署任务状态，请稍后同步");
        }
    }

    private void requireAbolishParent(FadadaSigningGateway.TaskStatus child, String originalId) {
        if (!originalId.equals(child.originalSignTaskId())) {
            throw new BusinessException("无法确认作废协议与原合同的对应关系，合同保持只读");
        }
    }

    private boolean retiredAbolishTask(FadadaContractSignTaskDO task, String childId) {
        // Use a current read: a callback may have read its task before waiting for
        // the contract lock held by the transaction that completed recovery.
        return !jdbc.queryForList("""
                SELECT sign_task_id FROM fadada_cancelled_abolish_task
                WHERE sign_task_id = ? AND contract_id = ? AND version_no = ? AND original_sign_task_id = ?
                FOR UPDATE
                """, String.class, childId, task.getContractId(), taskVersion(task), task.getSignTaskId()).isEmpty();
    }

    private void completeAbolish(FadadaContractSignTaskDO task, TradeContractDO contract) {
        tradeService.voidAfterElectronicAbolish(contract.getId(), taskVersion(task), safeUserId(contract));
        contract.setStatus("VOIDED");
        task.setProviderStatus("revoked");
        task.setLastError("");
        task.setFinishedAt(LocalDateTime.now());
    }

    private ContractSigningRespDTO payload(TradeContractDO contract, FadadaContractSignTaskDO task) {
        String status = task == null ? "WAITING_AUTH" : task.getProviderStatus();
        long companyId = AuthContext.companyId() == null ? contract.getCompanyId() : AuthContext.companyId();
        String actorStatus = task == null ? null : actorStatus(task, companyId);
        boolean pending = "PENDING".equals(contract.getStatus());
        boolean active = "ACTIVE".equals(contract.getStatus());
        Long approvedCount = active ? (bilateralState != null ? (long) bilateralState.approvedVoids(contract.getId(), false).size() : jdbc.queryForObject("""
                SELECT COUNT(1) FROM bilateral_action_request
                WHERE contract_id = ? AND biz_type = 'CONTRACT' AND action_type = 'VOID'
                  AND status = 'APPROVED'
                """, Long.class, contract.getId())) : 0L;
        boolean abolishApproved = approvedCount != null && approvedCount > 0;
        Long recoveryCount = active ? (bilateralState != null ? (long) bilateralState.pendingResumes(contract.getId(), true, false).size() : jdbc.queryForObject("""
                SELECT COUNT(1) FROM bilateral_action_request
                WHERE contract_id = ? AND biz_type = 'CONTRACT' AND action_type = 'RESUME'
                  AND status = 'PENDING'
                """, Long.class, contract.getId())) : 0L;
        boolean recoveryPending = recoveryCount != null && recoveryCount > 0;
        boolean abolishing = active && task != null && hasText(task.getAbolishedSignTaskId())
                && !"revoked".equalsIgnoreCase(status);
        boolean firstSignerReady = contract.getCompanyId().equals(companyId)
                || task != null && isSigned(task.getInitiatorSignStatus());
        boolean canSign = (pending && firstSignerReady && !isSigned(actorStatus)
                && (task == null || (!terminal(status) && !hasText(task.getAbolishedSignTaskId()))))
                || (abolishing && !isSigned(actorStatus))
                || (active && abolishApproved && task != null && hasText(task.getSignTaskId())
                    && !hasText(task.getAbolishedSignTaskId()));
        canSign = canSign && !recoveryPending && !"ABOLISH_CREATION_UNCERTAIN".equals(status)
                && !"revoked".equalsIgnoreCase(status);
        String displayStatus = pending && task != null && terminal(status)
                ? "task_finished".equals(status) && task.getArchivedAt() == null ? "签署结果确认中" : statusText(status)
                : pending && !firstSignerReady ? "等待发起方签署"
                : active && "revoked".equalsIgnoreCase(status) ? "作废结果待同步"
                : active && abolishApproved && !abolishing ? "待签署作废协议"
                : task != null && "task_finished".equals(status) && task.getArchivedAt() == null
                    ? "签署结果确认中" : statusText(status);
        return new ContractSigningRespDTO(String.valueOf(contract.getId()), status, displayStatus,
                task == null ? null : task.getInitiatorSignStatus(),
                task == null ? null : task.getCounterpartySignStatus(), canSign,
                pending && contract.getCompanyId().equals(companyId),
                active && task != null && hasText(task.getSignTaskId()) && !hasText(task.getAbolishedSignTaskId()),
                abolishApproved,
                task != null && task.getArchivedAt() != null,
                task == null || !hasText(task.getLastError()) ? null : task.getLastError());
    }

    private int version(TradeContractDO contract) {
        return contract.getVersionNo() == null ? 1 : contract.getVersionNo();
    }

    record RetiredAbolishTask(long contractId, int versionNo, String originalSignTaskId) {}

    private boolean syncCancelledAbolishTask(String signTaskId, boolean contractLocked) {
        var rows = jdbc.query("""
                SELECT contract_id, version_no, original_sign_task_id
                FROM fadada_cancelled_abolish_task WHERE sign_task_id = ?
                """ + (contractLocked ? " FOR UPDATE" : ""),
                (rs, row) -> new RetiredAbolishTask(rs.getLong("contract_id"), rs.getInt("version_no"),
                        rs.getString("original_sign_task_id")), signTaskId);
        if (rows.isEmpty()) return false;
        RetiredAbolishTask retired = rows.get(0);
        TradeContractDO contract = contractMapper.selectByIdForUpdate(retired.contractId());
        if (contract == null || version(contract) != retired.versionNo()) return true;
        FadadaSigningGateway.TaskStatus remote = gateway.status(signTaskId);
        requireRemoteTask(remote, signTaskId);
        requireAbolishParent(remote, retired.originalSignTaskId());
        if ("task_finished".equalsIgnoreCase(remote.status()) || "revoked".equalsIgnoreCase(remote.status())) {
            // A real completion arriving after recovery wins over local recovery state.
            tradeService.voidAfterElectronicAbolish(contract.getId(), version(contract), safeUserId(contract));
            taskMapper.update(new LambdaUpdateWrapper<FadadaContractSignTaskDO>()
                    .eq(FadadaContractSignTaskDO::getContractId, contract.getId())
                    .eq(FadadaContractSignTaskDO::getVersionNo, version(contract))
                    .set(FadadaContractSignTaskDO::getProviderStatus, "revoked")
                    .set(FadadaContractSignTaskDO::getFinishedAt, LocalDateTime.now()));
        } else if (!ContractAbolishRecoveryService.stopped(remote.status())) {
            throw new BusinessException("已取消作废任务的远程状态发生变化，需要核实处理");
        }
        return true;
    }

    private int taskVersion(FadadaContractSignTaskDO task) {
        return task.getVersionNo() == null ? 1 : task.getVersionNo();
    }

    private ContractRespDTO signingSnapshot(TradeContractDO contract, FadadaContractSignTaskDO task) {
        try {
            ContractRespDTO snapshot = task != null && hasText(task.getContractSnapshot())
                    ? objectMapper.readValue(task.getContractSnapshot(), ContractRespDTO.class)
                    : tradeService.contractForElectronicSignature(contract.getId(), contract.getCompanyId());
            if (snapshot == null || !String.valueOf(contract.getId()).equals(snapshot.id())
                    || snapshot.versionNo() == null || snapshot.versionNo() != version(contract)) {
                throw new BusinessException("签署快照与合同版本不一致");
            }
            return snapshot;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("签署合同快照读取失败");
        }
    }

    private String serializeSnapshot(ContractRespDTO snapshot) {
        try { return objectMapper.writeValueAsString(snapshot); }
        catch (Exception exception) { throw new BusinessException("签署合同快照保存失败"); }
    }

    private FadadaContractSignTaskDO find(TradeContractDO contract) {
        return find(contract, false);
    }

    private FadadaContractSignTaskDO find(TradeContractDO contract, boolean lock) {
        return taskMapper.selectOne(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .eq(FadadaContractSignTaskDO::getContractId, contract.getId())
                .eq(FadadaContractSignTaskDO::getVersionNo, contract.getVersionNo() == null ? 1 : contract.getVersionNo())
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
    }

    private void reconcileAbolishIntent(FadadaContractSignTaskDO task) {
        var pending = abolishIntents == null ? List.<ContractAbolishIntentService.PendingIntent>of()
                : abolishIntents.pending(task);
        if (pending.isEmpty() && !"ABOLISH_CREATION_UNCERTAIN".equals(task.getProviderStatus())) return;
        var parent = gateway.status(task.getSignTaskId());
        String childId = parent == null ? null : parent.abolishedSignTaskId();
        if (!hasText(childId) || pending.stream().anyMatch(item -> childId.equals(item.previousTaskId()))) {
            throw new BusinessException("作废任务创建结果尚未确认，请稍后刷新签署状态或核实电子签记录");
        }
        var child = gateway.status(childId);
        if (child == null || !task.getSignTaskId().equals(child.originalSignTaskId())
                || !childId.equals(child.signTaskId())) {
            throw new BusinessException("无法确认作废协议与原合同的对应关系，合同保持只读");
        }
        task.setAbolishedSignTaskId(childId);
        task.setProviderStatus("abolishing");
        task.setInitiatorSignStatus(null);
        task.setCounterpartySignStatus(null);
        task.setLastError("");
        taskMapper.update(task, new LambdaUpdateWrapper<FadadaContractSignTaskDO>()
                .eq(FadadaContractSignTaskDO::getId, task.getId())
                .set(FadadaContractSignTaskDO::getInitiatorSignStatus, null)
                .set(FadadaContractSignTaskDO::getCounterpartySignStatus, null));
        for (var intent : pending) abolishIntents.confirm(intent.id(), childId);
    }

    private boolean recoverUnknownAbolishCallback(String childId) {
        if (abolishIntents == null) return false;
        var child = gateway.status(childId);
        if (child == null || !hasText(child.originalSignTaskId()) || !childId.equals(child.signTaskId())) return false;
        var task = taskMapper.selectOne(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .eq(FadadaContractSignTaskDO::getSignTaskId, child.originalSignTaskId()).last("LIMIT 1"));
        if (task == null) return false;
        var contract = contractMapper.selectByIdForUpdate(task.getContractId());
        if (contract == null || version(contract) != taskVersion(task)) return false;
        task = find(contract, true);
        if (task == null || abolishIntents.pending(task).isEmpty()) return false;
        reconcileAbolishIntent(task);
        if (!childId.equals(task.getAbolishedSignTaskId())) return false;
        sync(task, contract, false);
        return true;
    }

    private TradeContractDO requireParty(Long contractId, long companyId) {
        return requireParty(contractId, companyId, false);
    }

    private TradeContractDO requireParty(Long contractId, long companyId, boolean lock) {
        TradeContractDO contract = lock ? contractMapper.selectByIdForUpdate(contractId)
                : contractMapper.selectById(contractId);
        if (contract == null || (!Long.valueOf(companyId).equals(contract.getCompanyId())
                && !Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId()))) {
            throw new BusinessException("合同不存在");
        }
        return contract;
    }

    private CompanyRespDTO requireCompany(Long companyId) {
        CompanyRespDTO company = companyMapper.selectById(companyId);
        if (company == null) throw new BusinessException("签署企业不存在");
        return company;
    }

    private String actorId(FadadaContractSignTaskDO task, long companyId) {
        if (task.getInitiatorCompanyId().equals(companyId)) return task.getInitiatorActorId();
        if (task.getCounterpartyCompanyId().equals(companyId)) return task.getCounterpartyActorId();
        throw new BusinessException("当前企业不是合同签署方");
    }

    private String actorStatus(FadadaContractSignTaskDO task, long companyId) {
        return task.getInitiatorCompanyId().equals(companyId)
                ? task.getInitiatorSignStatus() : task.getCounterpartySignStatus();
    }

    private boolean isSigned(String status) {
        return "signed".equalsIgnoreCase(status) || "sign_completed".equalsIgnoreCase(status)
                || "completed".equalsIgnoreCase(status);
    }

    private boolean terminal(String status) {
        return status != null && (status.toLowerCase(java.util.Locale.ROOT).contains("terminated")
                || status.toLowerCase(java.util.Locale.ROOT).contains("expired")
                || status.equalsIgnoreCase("task_finished") || status.equalsIgnoreCase("revoked"));
    }

    private boolean abolishRetryable(String status) {
        return status != null && (status.toLowerCase().contains("terminated")
                || status.toLowerCase().contains("expired"));
    }

    private String statusText(String status) {
        if (status == null) return "待准备";
        if ("ABOLISH_CREATION_UNCERTAIN".equals(status)) return "作废任务结果待核实";
        if ("ABOLISH_sign_completed".equals(status)) return "双方已签署，正在确认作废结果";
        if (status.startsWith("ABOLISH_") && abolishRetryable(status)) return "作废签署已中止，可重试或申请恢复履约";
        if (status.startsWith("ABOLISH_") || "abolishing".equals(status)) return "作废签署中";
        return switch (status) {
            case "CREATING" -> "正在准备签署文件";
            case "CREATE_FAILED" -> "签署任务准备失败";
            case "sign_progress", "finish_creation" -> "签署中";
            case "sign_completed" -> "双方已签署，正在归档";
            case "task_finished" -> "已签署并归档";
            case "task_terminated" -> "签署已终止";
            case "expired", "task_expired" -> "签署已过期";
            case "revoked" -> "已作废";
            default -> "待完成企业认证和印章设置";
        };
    }

    

    private void requireReady() {
        if (!properties.isEnabled() || !hasText(properties.getAppId()) || !hasText(properties.getAppSecret())
                || !hasText(properties.getServerUrl()) || !hasText(properties.getCallbackUrl())) {
            throw new BusinessException("电子签服务尚未配置完整");
        }
    }

    private long safeUserId(TradeContractDO contract) {
        return contract.getInitiatedBy() == null ? 1L : contract.getInitiatedBy();
    }
    private String safeReason(String reason) {
        String value = hasText(reason) ? reason.trim() : "双方协商作废合同";
        if (value.length() > 200) throw new BusinessException("合同作废原因不能超过200字");
        return value;
    }
    private String shortMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return hasText(value) ? value.substring(0, Math.min(512, value.length())) : "签署任务创建失败";
    }
    private void validateUrl(String value) {
        if (!hasText(value) || !value.startsWith("https://")) throw new BusinessException("电子签服务地址无效");
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
