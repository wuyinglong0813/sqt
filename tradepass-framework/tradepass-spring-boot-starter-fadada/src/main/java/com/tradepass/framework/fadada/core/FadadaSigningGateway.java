package com.tradepass.framework.fadada.core;

import java.util.List;

public interface FadadaSigningGateway {
    CreatedTask createTask(CreateTaskCommand command);
    /** Read-only recovery. A missing result never authorizes another create request. */
    default CreatedTask findCreatedTask(TaskRecoveryCommand command) {
        throw new UnsupportedOperationException("签署任务恢复查询尚未实现");
    }
    String actorUrl(String signTaskId, String actorId, String clientUserId, String redirectMiniAppUrl);
    TaskStatus status(String signTaskId);
    byte[] downloadSignedPdf(String signTaskId, String ownerOpenCorpId, String fileName);
    byte[] downloadSignedPreviewPage(String signTaskId, String ownerOpenCorpId);
    default List<byte[]> downloadSignedPreviewPages(String signTaskId, String ownerOpenCorpId) {
        throw new UnsupportedOperationException("合同完整预览尚未实现");
    }
    void cancel(String signTaskId, String reason);
    /** Prevent an unsigned participant from signing while its task is being reconciled. */
    default void blockActor(String signTaskId, String actorId) {
        throw new UnsupportedOperationException("签署参与方阻塞尚未实现");
    }
    /** Restore only a participant whose blocking was initiated by this application. */
    default void unblockActor(String signTaskId, String actorId) {
        throw new UnsupportedOperationException("签署参与方解阻尚未实现");
    }
    String abolish(String signTaskId, String actorId, String reason, String callbackUrl);
    default List<String> actorSealIds(String signTaskId, String actorId) {
        throw new UnsupportedOperationException("签署任务印章查询尚未实现");
    }

    record CreateTaskCommand(byte[] pdf, String fileName, String subject, String contractReference,
                             String initiatorOpenCorpId, String initiatorActorId,
                             String initiatorName, String initiatorSealId,
                             String counterpartyOpenCorpId, String counterpartyActorId,
                             String counterpartyName, String counterpartySealId,
                             String supplierActorId, String buyerActorId, String callbackUrl,
                             SealPosition supplierSealPosition, SealPosition buyerSealPosition,
                             String providerInitiatorOpenCorpId) {
        public CreateTaskCommand(byte[] pdf, String fileName, String subject, String contractReference,
                                 String initiatorOpenCorpId, String initiatorActorId, String initiatorName, String initiatorSealId,
                                 String counterpartyOpenCorpId, String counterpartyActorId, String counterpartyName, String counterpartySealId,
                                 String supplierActorId, String buyerActorId, String callbackUrl,
                                 SealPosition supplierSealPosition, SealPosition buyerSealPosition) {
            this(pdf, fileName, subject, contractReference, initiatorOpenCorpId, initiatorActorId, initiatorName, initiatorSealId,
                    counterpartyOpenCorpId, counterpartyActorId, counterpartyName, counterpartySealId,
                    supplierActorId, buyerActorId, callbackUrl, supplierSealPosition, buyerSealPosition, initiatorOpenCorpId);
        }
        public CreateTaskCommand(byte[] pdf, String fileName, String subject, String contractReference,
                                 String initiatorOpenCorpId, String initiatorActorId, String initiatorName, String initiatorSealId,
                                 String counterpartyOpenCorpId, String counterpartyActorId, String counterpartyName, String counterpartySealId,
                                 String supplierActorId, String buyerActorId, String callbackUrl) {
            this(pdf, fileName, subject, contractReference, initiatorOpenCorpId, initiatorActorId, initiatorName, initiatorSealId,
                    counterpartyOpenCorpId, counterpartyActorId, counterpartyName, counterpartySealId,
                    supplierActorId, buyerActorId, callbackUrl, null, null);
        }
        public void validate() {
            if (pdf == null || pdf.length == 0 || pdf.length > 50 * 1024 * 1024) {
                throw new com.tradepass.framework.common.exception.BusinessException("签署合同文件必须非空且不超过50MB");
            }
            required(fileName, 200, "合同文件名");
            if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                    || fileName.matches(".*[\\\\/:*?\"<>|\\r\\n].*")) {
                throw new com.tradepass.framework.common.exception.BusinessException("签署合同文件名必须为有效的PDF文件名");
            }
            required(subject, 200, "合同主题"); required(contractReference, 128, "合同业务编号");
            required(initiatorName, 128, "发起企业名称"); required(counterpartyName, 128, "相对企业名称");
            required(initiatorOpenCorpId, 64, "发起企业标识"); required(counterpartyOpenCorpId, 64, "相对企业标识");
            required(providerInitiatorOpenCorpId, 64, "服务商签署发起企业标识");
            required(initiatorActorId, 32, "发起参与方标识"); required(counterpartyActorId, 32, "相对参与方标识");
            required(supplierActorId, 32, "供方标识"); required(buyerActorId, 32, "需方标识");
            if (supplierActorId.equals(buyerActorId) || initiatorActorId.equals(counterpartyActorId)
                    || !java.util.Set.of(supplierActorId, buyerActorId).equals(java.util.Set.of(initiatorActorId, counterpartyActorId))) {
                throw new com.tradepass.framework.common.exception.BusinessException("合同参与方标识不完整或重复");
            }
            for (String seal : new String[]{initiatorSealId, counterpartySealId}) {
                try { if (Long.parseLong(seal) <= 0) throw new NumberFormatException(); }
                catch (RuntimeException invalid) {
                    throw new com.tradepass.framework.common.exception.BusinessException("企业电子印章标识无效");
                }
            }
            if (supplierSealPosition == null || buyerSealPosition == null) {
                throw new com.tradepass.framework.common.exception.BusinessException("签署文件缺少实际签章位置");
            }
            supplierSealPosition.validate(); buyerSealPosition.validate();
        }

        private static void required(String value, int max, String name) {
            if (value == null || value.isBlank() || value.length() > max) {
                throw new com.tradepass.framework.common.exception.BusinessException(name + "为空或超过服务商长度限制");
            }
        }
    }
    /** Official 96-DPI coordinates measured from the PDF page's top-left corner. */
    record SealPosition(int pageNo, String x, String y) {
        public void validate() {
            try {
                if (pageNo < 1 || new java.math.BigDecimal(x).signum() < 0 || new java.math.BigDecimal(y).signum() < 0) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                throw new com.tradepass.framework.common.exception.BusinessException("签署文件签章位置无效");
            }
        }
    }
    record CreatedTask(String signTaskId, String fileId, String docId) {}
    record TaskRecoveryCommand(String contractReference, String initiatorOpenCorpId, String initiatorActorId,
                               String counterpartyOpenCorpId, String counterpartyActorId, String fileName,
                               String providerInitiatorOpenCorpId) {
        public TaskRecoveryCommand(String contractReference, String initiatorOpenCorpId, String initiatorActorId,
                                   String counterpartyOpenCorpId, String counterpartyActorId, String fileName) {
            this(contractReference, initiatorOpenCorpId, initiatorActorId, counterpartyOpenCorpId,
                    counterpartyActorId, fileName, initiatorOpenCorpId);
        }
    }
    record ActorStatus(String actorId, String signStatus, String signTime, String blockStatus) {
        public ActorStatus(String actorId, String signStatus) {
            this(actorId, signStatus, null, null);
        }
    }
    record TaskStatus(String signTaskId, String status, List<ActorStatus> actors,
                      String originalSignTaskId, String abolishedSignTaskId) {
        public TaskStatus(String signTaskId, String status, List<ActorStatus> actors) {
            this(signTaskId, status, actors, null, null);
        }
    }
}
