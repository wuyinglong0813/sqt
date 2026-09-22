package com.tradepass.framework.fadada.core;

import java.util.List;

public interface FadadaSigningGateway {
    CreatedTask createTask(CreateTaskCommand command);
    String actorUrl(String signTaskId, String actorId, String clientUserId, String redirectMiniAppUrl);
    TaskStatus status(String signTaskId);
    byte[] downloadSignedPdf(String signTaskId, String ownerOpenCorpId, String fileName);
    byte[] downloadSignedPreviewPage(String signTaskId, String ownerOpenCorpId);
    void cancel(String signTaskId, String reason);
    String abolish(String signTaskId, String reason, String callbackUrl);

    record CreateTaskCommand(byte[] pdf, String fileName, String subject, String contractReference,
                             String initiatorOpenCorpId, String initiatorActorId,
                             String initiatorName, String initiatorSealId,
                             String counterpartyOpenCorpId, String counterpartyActorId,
                             String counterpartyName, String counterpartySealId,
                             String supplierActorId, String buyerActorId, String callbackUrl) {}
    record CreatedTask(String signTaskId, String fileId, String docId) {}
    record ActorStatus(String actorId, String signStatus) {}
    record TaskStatus(String signTaskId, String status, List<ActorStatus> actors,
                      String originalSignTaskId, String abolishedSignTaskId) {
        public TaskStatus(String signTaskId, String status, List<ActorStatus> actors) {
            this(signTaskId, status, actors, null, null);
        }
    }
}
