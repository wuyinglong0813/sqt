package com.tradepass.module.contract.service.signing;

import com.tradepass.module.contract.api.signing.FadadaContractSigningOperations;
import com.tradepass.module.contract.api.signing.FadadaContractSigningOperations.*;
import com.tradepass.module.contract.service.abolish.ContractAbolishIntentService;
import com.tradepass.module.contract.service.abolish.ContractAbolishRecoveryService;
import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.contract.TradeService;
import com.tradepass.module.contract.service.membership.MembershipService;

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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

@Service
public class FadadaContractSigningServiceImpl implements FadadaContractSigningService {
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

    /** Prepare only the signer's identity and authority before any billable confirmation. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public java.util.Map<String, Object> signingPrerequisites(Long contractId) {
        requireReady();
        long companyId = AuthContext.requireCompanyId();
        accessControl.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId, true);
        if (!"PENDING".equals(contract.getStatus())) throw new BusinessException("当前合同不在待签署状态");
        FadadaContractSignTaskDO task = restorePreviousIntent(contract, find(contract, true));
        boolean newTask = task == null || !hasText(task.getSignTaskId());
        if (task != null && terminal(task.getProviderStatus())) {
            throw new BusinessException("签署任务已结束，请刷新合同状态");
        }
        if (!newTask && isSigned(actorStatus(task, companyId))) {
            throw new BusinessException("当前企业已完成签署，请等待对方签署");
        }
        if (!contract.getCompanyId().equals(companyId)
                && (newTask || !isSigned(task.getInitiatorSignStatus()))) {
            throw new BusinessException("请等待合同发起方先完成签署");
        }
        // Existing tasks keep their original provider seals. Do not reject them
        // because the company's current default seal changed after task creation.
        if (newTask) {
            requireNewSigningConfiguration(contract);
            companyService.requireCurrentSigningAuthorization(companyId);
        }
        else companyService.requireCurrentSigningMember(companyId);
        return java.util.Map.of("status", "READY");
    }

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
        if (membership == null) {
            requireNewSigningConfiguration(contract);
            return java.util.Map.of("canSign", true, "newTask", true, "message", "进入合同签署");
        }
        MembershipService.Reservation existing = membership.existing(contractId, version(contract));
        if (existing != null && "CONSUMED".equals(existing.status()))
            return java.util.Map.of("canSign", true, "newTask", false, "message", "恢复已有签署，不再消耗额度");
        if (existing != null && !"RELEASED".equals(existing.status()))
            return java.util.Map.of("canSign", false, "newTask", false, "message", pendingMessage(contract));
        requireNewSigningConfiguration(contract);
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ServiceUrlPayload signUrl(Long contractId) {
        requireReady();
        long companyId = AuthContext.requireCompanyId();
        accessControl.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId, true);
        if (!"PENDING".equals(contract.getStatus())) throw new BusinessException("当前合同不在待签署状态");
        // Local contract permissions do not establish membership of the provider enterprise.
        // Check before preparing a billable task or issuing a personal signing link.
        FadadaContractSignTaskDO task = find(contract, true);
        if (contract.getCompanyId().equals(companyId) && (task == null || !hasText(task.getSignTaskId()))) {
            // A new task uses the currently configured seal. Existing signing and
            // abolishment tasks retain their original provider seal configuration.
            if (hasPreviousIntent(contract)) companyService.requireCurrentSigningMember(companyId);
            else companyService.requireCurrentSigningAuthorization(companyId);
            task = prepare(contract);
        } else companyService.requireCurrentSigningMember(companyId);
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContractSigningRespDTO syncCurrent(Long contractId) {
        long companyId = AuthContext.requireCompanyId();
        accessControl.requireAnyPermission(companyId, "contract_view", "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId, true);
        FadadaContractSignTaskDO task = find(contract, true);
        task = restorePreviousIntent(contract, task);
        if (task == null || !hasText(task.getSignTaskId())
                || !("PENDING".equals(contract.getStatus()) || "ACTIVE".equals(contract.getStatus()))) {
            return payload(contract, task);
        }
        requireReady();
        task = sync(task, contract);
        return payload(contractMapper.selectByIdForUpdate(contractId), task);
    }

    public SignedPreview signedPreview(Long contractId) {
        PreviewBundle preview = signedPreviewBundle(contractId);
        return previewPage(preview.data(), java.nio.ByteBuffer.wrap(preview.data()).getInt());
    }

    public SignedPreviewManifest signedPreviewManifest(Long contractId) {
        PreviewBundle preview = signedPreviewBundle(contractId);
        return new SignedPreviewManifest(java.nio.ByteBuffer.wrap(preview.data()).getInt(), preview.archiveKey());
    }

    public SignedPreview signedPreviewPage(Long contractId, int page, String archiveKey) {
        if (page < 1) throw new BusinessException("合同预览页码不正确");
        PreviewBundle preview = signedPreviewBundle(contractId);
        if (!preview.archiveKey().equals(archiveKey)) {
            throw new BusinessException(409, "合同归档版本已更新，请重新加载完整合同");
        }
        return previewPage(preview.data(), page);
    }

    private PreviewBundle signedPreviewBundle(Long contractId) {
        requireReady();
        long companyId = AuthContext.requireCompanyId();
        accessControl.requireAnyPermission(companyId, "contract_view", "contract_sign");
        TradeContractDO contract = requireParty(contractId, companyId);
        FadadaContractSignTaskDO task = find(contract);
        if (task == null || !hasText(task.getSignTaskId()) || task.getArchivedAt() == null) {
            throw new BusinessException("合同真实签章页尚未归档");
        }
        String ownerOpenCorpId = providerInitiator(task, contract);
        // Pack the complete immutable archive once. Page lookup skips bytes without
        // unzipping or downloading the provider archive again for each file chunk.
        byte[] pages = signedPreviewCache.get(
                "signed-preview-pages:" + task.getSignTaskId(),
                () -> packPreviewPages(gateway.downloadSignedPreviewPages(task.getSignTaskId(), ownerOpenCorpId)));
        return new PreviewBundle(task.getSignTaskId(), pages);
    }

    private record PreviewBundle(String archiveKey, byte[] data) {}

    private static byte[] packPreviewPages(List<byte[]> pages) {
        if (pages == null || pages.isEmpty()) throw new BusinessException("合同完整预览尚未生成");
        long size = Integer.BYTES;
        for (byte[] page : pages) {
            if (page == null || page.length == 0) throw new BusinessException("合同预览页面为空");
            size += Integer.BYTES + (long) page.length;
        }
        if (size > 64L * 1024 * 1024) throw new BusinessException("合同预览内容过大，请查看归档 PDF");
        var buffer = java.nio.ByteBuffer.allocate((int) size);
        buffer.putInt(pages.size());
        for (byte[] page : pages) buffer.putInt(page.length).put(page);
        return buffer.array();
    }

    private static SignedPreview previewPage(byte[] packed, int page) {
        var buffer = java.nio.ByteBuffer.wrap(packed);
        int count = buffer.getInt();
        if (page < 1 || page > count) throw new BusinessException("合同预览页码超出范围");
        for (int number = 1; number <= count; number++) {
            int length = buffer.getInt();
            if (number == page) {
                byte[] image = new byte[length];
                buffer.get(image);
                return new SignedPreview("合同第" + page + "页.png", image);
            }
            buffer.position(buffer.position() + length);
        }
        throw new BusinessException("合同预览页码超出范围");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
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

    /** Compatibility endpoint for old clients; local void approval no longer creates a provider task. */
    public ServiceUrlPayload abolishUrl(Long contractId, String reason) {
        long companyId = AuthContext.requireCompanyId();
        accessControl.requirePermission(companyId, "contract_sign");
        requireParty(contractId, companyId);
        throw new BusinessException("合同作废已改为在商签通内由双方确认，请返回合同详情处理");
    }

    private FadadaContractSignTaskDO prepare(TradeContractDO contract) {
        FadadaContractSignTaskDO existing = find(contract, true);
        if (existing != null && hasText(existing.getSignTaskId())) return existing;
        MembershipService.Reservation receipt = membership == null ? null : membership.existing(contract.getId(), version(contract));
        if (receipt != null && ("RESERVED".equals(receipt.status()) || "UNCERTAIN".equals(receipt.status()))) {
            if (!hasText(receipt.snapshot()) || !hasText(receipt.sha256())) {
                throw new BusinessException("原签署记录缺少冻结文件依据，请联系平台核实；不会重复创建签署");
            }
            var frozenTask = new FadadaContractSignTaskDO();
            frozenTask.setContractSnapshot(receipt.snapshot());
            frozenTask.setProviderInitiatorOpenCorpId(receipt.providerInitiatorOpenCorpId());
            ContractRespDTO frozen = signingSnapshot(contract, frozenTask);
            if (!String.valueOf(contract.getCompanyId()).equals(frozen.companyId())
                    || !String.valueOf(contract.getCounterpartyCompanyId()).equals(frozen.counterpartyCompanyId())) {
                throw new BusinessException("原签署快照与合同企业不一致，请联系平台核实");
            }
            String initiatorActor = "SALE".equalsIgnoreCase(frozen.direction()) ? "supplier" : "buyer";
            var recovered = gateway.findCreatedTask(new FadadaSigningGateway.TaskRecoveryCommand(
                    "contract-" + contract.getId() + "-v" + version(contract),
                    companyService.requireVerified(contract.getCompanyId()).getOpenCorpId(), initiatorActor,
                    companyService.requireVerified(contract.getCounterpartyCompanyId()).getOpenCorpId(),
                    "supplier".equals(initiatorActor) ? "buyer" : "supplier", pdfService.fileName(frozen),
                    providerInitiator(frozenTask, contract)));
            if (recovered == null || !hasText(recovered.signTaskId()) || !hasText(recovered.fileId())
                    || !"contract-doc".equals(recovered.docId())) {
                throw new BusinessException(pendingMessage(contract));
            }
            membership.confirm(receipt.id(), recovered);
            receipt = new MembershipService.Reservation(receipt.id(), "CONSUMED", false, recovered.signTaskId(),
                    recovered.fileId(), recovered.docId(), receipt.snapshot(), receipt.sha256(),
                    receipt.providerInitiatorOpenCorpId());
        }
        if (receipt != null && "CONSUMED".equals(receipt.status())) {
            FadadaContractSignTaskDO restored = existing == null ? new FadadaContractSignTaskDO() : existing;
            restored.setContractId(contract.getId());
            restored.setVersionNo(version(contract));
            restored.setContractSnapshot(receipt.snapshot());
            restored.setProviderInitiatorOpenCorpId(receipt.providerInitiatorOpenCorpId());
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
        requirePlatformQuotaService();
        String providerInitiatorOpenCorpId = properties.signingInitiatorOpenCorpId(initiatorIdentity.getOpenCorpId());
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
                signingDocument.supplierPosition(), signingDocument.buyerPosition(), providerInitiatorOpenCorpId);
        createCommand.validate(); // Fail locally before reserving quota or creating a remote task.
        String sha256 = FileTypeInspector.sha256(pdf);
        FadadaContractSignTaskDO task = existing == null ? new FadadaContractSignTaskDO() : existing;
        task.setContractSnapshot(serializeSnapshot(payload));
        task.setProviderInitiatorOpenCorpId(providerInitiatorOpenCorpId);
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
                : membership.reserve(initiator, contract.getId(), version(contract), task.getContractSnapshot(), sha256,
                        providerInitiatorOpenCorpId);
        try {
            FadadaSigningGateway.CreatedTask created;
            if (reservation != null && !reservation.createAllowed()) {
                created = new FadadaSigningGateway.CreatedTask(
                        reservation.signTaskId(), reservation.sourceFileId(), reservation.docId());
                task.setContractSnapshot(reservation.snapshot());
                task.setProviderInitiatorOpenCorpId(reservation.providerInitiatorOpenCorpId());
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
                } else if (exception instanceof com.tradepass.framework.fadada.core.FadadaProviderQuotaException) {
                    membership.uncertain(reservation.id(), com.tradepass.framework.fadada.core.FadadaProviderQuotaException.MESSAGE);
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

    private boolean hasPreviousIntent(TradeContractDO contract) {
        if (membership == null) return false;
        var receipt = membership.existing(contract.getId(), version(contract));
        return receipt != null && java.util.Set.of("RESERVED", "UNCERTAIN", "CONSUMED").contains(receipt.status());
    }

    private String pendingMessage(TradeContractDO contract) {
        String message = membership == null ? null : membership.pendingMessage(contract.getId(), version(contract));
        return hasText(message) ? message : "上次签署创建结果待核实，请稍后刷新；系统不会重复创建或扣费";
    }

    private FadadaContractSignTaskDO restorePreviousIntent(TradeContractDO contract, FadadaContractSignTaskDO task) {
        if ("PENDING".equals(contract.getStatus()) && (task == null || !hasText(task.getSignTaskId()))
                && hasPreviousIntent(contract)) {
            requireReady();
            // prepare's durable-intent branch only restores the existing task.
            // A missing provider result keeps the reservation and never creates.
            return prepare(contract);
        }
        return task;
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
                String ownerOpenCorpId = providerInitiator(task, contract);
                ContractRespDTO payload = signingSnapshot(contract, task);
                task.setContractSnapshot(serializeSnapshot(payload));
                byte[] signed = gateway.downloadSignedPdf(task.getSignTaskId(), ownerOpenCorpId,
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
        String providerStatus = task == null ? "WAITING_AUTH" : task.getProviderStatus();
        boolean voided = "VOIDED".equals(contract.getStatus());
        // A local business void does not change the provider's signed document or its status.
        String status = voided && !"revoked".equalsIgnoreCase(providerStatus) ? "VOIDED_LOCAL" : providerStatus;
        long companyId = AuthContext.companyId() == null ? contract.getCompanyId() : AuthContext.companyId();
        String actorStatus = task == null ? null : actorStatus(task, companyId);
        boolean pending = "PENDING".equals(contract.getStatus());
        boolean active = "ACTIVE".equals(contract.getStatus());
        boolean firstSignerReady = contract.getCompanyId().equals(companyId)
                || task != null && isSigned(task.getInitiatorSignStatus());
        boolean canSign = pending && firstSignerReady && !isSigned(actorStatus)
                && (task == null || (!terminal(status) && !hasText(task.getAbolishedSignTaskId())))
                && !"ABOLISH_CREATION_UNCERTAIN".equals(status) && !"revoked".equalsIgnoreCase(status);
        String partyText = task == null ? null : ContractSigningProgress.partyText(status, actorStatus,
                companyId == task.getInitiatorCompanyId() ? task.getCounterpartySignStatus() : task.getInitiatorSignStatus());
        String displayStatus = voided ? "已作废"
                : "COMPLETED".equals(contract.getStatus()) ? "已结束"
                : pending && task != null && terminal(status)
                    ? "task_finished".equals(status) && task.getArchivedAt() == null ? "签署结果确认中" : statusText(status)
                : pending && partyText != null ? partyText
                : pending && !firstSignerReady ? "等待发起方签署"
                : active ? "履约中" : statusText(status);
        return new ContractSigningRespDTO(String.valueOf(contract.getId()), status, displayStatus,
                task == null ? null : task.getInitiatorSignStatus(),
                task == null ? null : task.getCounterpartySignStatus(), canSign,
                pending && contract.getCompanyId().equals(companyId), active, false,
                task != null && task.getArchivedAt() != null,
                pending && task != null && hasText(task.getLastError()) ? task.getLastError() : null);
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
        if (contract == null || version(contract) != retired.versionNo()
                || !("PENDING".equals(contract.getStatus()) || "ACTIVE".equals(contract.getStatus()))) return true;
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
        if (!("PENDING".equals(contract.getStatus()) || "ACTIVE".equals(contract.getStatus()))) return true;
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

    /** A stored task/intent owns its provider identity even after a configuration change. */
    private String providerInitiator(FadadaContractSignTaskDO task, TradeContractDO contract) {
        if (hasText(task.getProviderInitiatorOpenCorpId())) return task.getProviderInitiatorOpenCorpId();
        // NULL is the migration marker for historical merchant-initiated tasks.
        return companyService.requireVerified(contract.getCompanyId()).getOpenCorpId();
    }

    private void requireNewSigningConfiguration(TradeContractDO contract) {
        requirePlatformQuotaService();
        if (properties.isPlatformInitiatedSigningEnabled()) {
            properties.signingInitiatorOpenCorpId(
                    companyService.requireVerified(contract.getCompanyId()).getOpenCorpId());
        }
    }

    private void requirePlatformQuotaService() {
        if (properties.isPlatformInitiatedSigningEnabled() && membership == null) {
            throw new BusinessException("企业签署额度服务尚未就绪，请联系平台");
        }
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
        return ContractSigningProgress.signed(status);
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
        if (status.startsWith("ABOLISH_") && abolishRetryable(status)) return "历史作废签署已中止";
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
    private String shortMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return hasText(value) ? value.substring(0, Math.min(512, value.length())) : "签署任务创建失败";
    }
    private void validateUrl(String value) {
        if (!hasText(value) || !value.startsWith("https://")) throw new BusinessException("电子签服务地址无效");
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
