package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.bean.common.OpenId;
import com.fasc.open.api.v5_1.client.SignTaskClient;
import com.fasc.open.api.v5_1.req.signtask.GetOwnerSignTaskListReq;
import com.fasc.open.api.v5_1.res.signtask.*;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SdkFadadaSigningRecoveryTest {
    @Test void platformRecoverySearchesTheFrozenOwnerAndStillVerifiesBothRealContractParties() throws Exception {
        var f = new Fixture();
        f.properties.setPlatformInitiatedSigningEnabled(true);
        f.properties.setOwnerEnterpriseOpenCorpId("new-platform-owner");
        f.detail.setInitiator(OpenId.getInstance("corp", "frozen-platform-owner"));
        when(f.client.getOwnerList(any())).thenAnswer(call -> {
            GetOwnerSignTaskListReq request = call.getArgument(0);
            assertThat(request.getOwnerId().getOpenId()).isEqualTo("frozen-platform-owner");
            return success(f.list);
        });

        assertThat(f.gateway.findCreatedTask(platformCommand()).signTaskId()).isEqualTo("original");
        verify(f.client, never()).create(any());
    }

    @ParameterizedTest @ValueSource(strings = {"owner-business", "owner-another", "supplier-platform", "buyer-platform"})
    void platformRecoveryRejectsAMismatchedOwnerOrAPlatformMistakenForASigningParty(String mismatch) throws Exception {
        var f = new Fixture(); f.detail.setInitiator(OpenId.getInstance("corp", "frozen-platform-owner"));
        switch (mismatch) {
            case "owner-business" -> f.detail.setInitiator(OpenId.getInstance("corp", "corp-3"));
            case "owner-another" -> f.detail.setInitiator(OpenId.getInstance("corp", "new-platform-owner"));
            case "supplier-platform" -> f.detail.getActors().get(0).getActorInfo().setActorOpenId("frozen-platform-owner");
            case "buyer-platform" -> f.detail.getActors().get(1).getActorInfo().setActorOpenId("frozen-platform-owner");
        }
        assertThatThrownBy(() -> f.gateway.findCreatedTask(platformCommand())).hasMessageContaining("与合同不一致");
        verify(f.client, never()).create(any());
    }

    @Test void legacyRecoveryCommandKeepsTheBusinessOwner() {
        var command = new FadadaSigningGateway.TaskRecoveryCommand("ref", "corp-3", "supplier", "corp-4", "buyer", "合同.pdf");
        assertThat(command.providerInitiatorOpenCorpId()).isEqualTo("corp-3");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @ValueSource(strings = {" ", "12345678901234567890123456789012345678901234567890123456789012345"})
    void recoveryRejectsInvalidProviderOwnerBeforeCallingTheProvider(String providerOwner) throws Exception {
        var f = new Fixture();
        var command = new FadadaSigningGateway.TaskRecoveryCommand("ref", "corp-3", "supplier", "corp-4", "buyer", "合同.pdf", providerOwner);
        assertThatThrownBy(() -> f.gateway.findCreatedTask(command)).hasMessageContaining("恢复参数不完整");
        verifyNoInteractions(f.client);
    }

    private static FadadaSigningGateway.TaskRecoveryCommand platformCommand() {
        return new FadadaSigningGateway.TaskRecoveryCommand("contract-12-v1", "corp-3", "supplier", "corp-4", "buyer", "合同.pdf", "frozen-platform-owner");
    }

    @Test void recoversOnlyTheUniqueTaskWithTheExpectedBusinessReferenceCompaniesActorsAndDocument() throws Exception {
        var f = new Fixture();
        when(f.client.getOwnerList(any())).thenAnswer(call -> {
            GetOwnerSignTaskListReq request = call.getArgument(0);
            assertThat(request.getOwnerId().getIdType()).isEqualTo("corp");
            assertThat(request.getOwnerId().getOpenId()).isEqualTo("corp-3");
            assertThat(request.getOwnerRole()).isEqualTo("initiator");
            assertThat(request.getListFilter()).isNull();
            assertThat(request.getListPageNo()).isEqualTo(1);
            assertThat(request.getListPageSize()).isEqualTo(100);
            return success(f.list);
        });

        assertThat(f.gateway.findCreatedTask(f.command)).isEqualTo(
                new FadadaSigningGateway.CreatedTask("original", "original-file", "contract-doc"));
        verify(f.client, never()).create(any());
    }

    @Test void anEmptySearchIsNotEvidenceThatAnotherTaskMayBeCreated() throws Exception {
        var f = new Fixture(); f.list.setTotalCount(0); f.list.setCountInPage(0); f.list.setSignTasks(List.of());
        assertThat(f.gateway.findCreatedTask(f.command)).isNull();
        verify(f.client, never()).getDetail(any());
        verify(f.client, never()).create(any());
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "missing-total", "wrong-page", "missing-page-count", "wrong-count", "too-many-pages", "incomplete"})
    void rejectsAmbiguousOrIncompleteSearchResults(String condition) throws Exception {
        var f = new Fixture();
        switch (condition) {
            case "duplicate" -> { f.list.setTotalCount(2); f.list.setCountInPage(2); f.list.setSignTasks(List.of(f.info, f.info)); }
            case "missing-total" -> f.list.setTotalCount(null);
            case "wrong-page" -> f.list.setListPageNo(2);
            case "missing-page-count" -> f.list.setListPageCount(null);
            case "wrong-count" -> f.list.setCountInPage(0);
            case "too-many-pages" -> f.list.setListPageCount(21);
            case "incomplete" -> f.list.setTotalCount(2);
        }
        assertThatThrownBy(() -> f.gateway.findCreatedTask(f.command)).hasMessageContaining("不会重复创建");
        verify(f.client, never()).getDetail(any());
        verify(f.client, never()).create(any());
    }

    @Test void unrelatedReferencesAreNotEvidenceOfARecoveryMatch() throws Exception {
        var f = new Fixture(); f.info.setTransReferenceId("another-contract");
        assertThat(f.gateway.findCreatedTask(f.command)).isNull();
        verify(f.client, never()).getDetail(any());
        verify(f.client, never()).create(any());
    }

    @Test void aUniqueMatchOnTheSecondPageRequiresCompleteEnumeration() throws Exception {
        var f = new Fixture();
        var unrelated = new SignTaskInfo(); unrelated.setSignTaskId("unrelated"); unrelated.setTransReferenceId("another");
        var first = page(1, 2, List.of(unrelated)); var second = page(2, 2, List.of(f.info));
        when(f.client.getOwnerList(any())).thenAnswer(call -> {
            GetOwnerSignTaskListReq request = call.getArgument(0);
            return success(request.getListPageNo() == 1 ? first : second);
        });
        assertThat(f.gateway.findCreatedTask(f.command).signTaskId()).isEqualTo("original");
        verify(f.client, times(2)).getOwnerList(any());
        verify(f.client, never()).create(any());
    }

    @Test void twoMatchingTasksAcrossPagesCannotBeRestored() throws Exception {
        var f = new Fixture();
        var duplicate = new SignTaskInfo(); duplicate.setSignTaskId("other-task"); duplicate.setTransReferenceId("contract-12-v1");
        when(f.client.getOwnerList(any())).thenAnswer(call -> {
            GetOwnerSignTaskListReq request = call.getArgument(0);
            return success(page(request.getListPageNo(), 2, List.of(request.getListPageNo() == 1 ? f.info : duplicate)));
        });
        assertThatThrownBy(() -> f.gateway.findCreatedTask(f.command)).hasMessageContaining("不会重复创建");
        verify(f.client, never()).getDetail(any()); verify(f.client, never()).create(any());
    }

    @Test void findingAMatchDoesNotAllowIgnoringALaterPageFailure() throws Exception {
        var f = new Fixture();
        when(f.client.getOwnerList(any())).thenAnswer(call -> {
            GetOwnerSignTaskListReq request = call.getArgument(0);
            return request.getListPageNo() == 1 ? success(page(1, 2, List.of(f.info))) : null;
        });
        assertThatThrownBy(() -> f.gateway.findCreatedTask(f.command)).hasMessageContaining("失败");
        verify(f.client, never()).getDetail(any()); verify(f.client, never()).create(any());
    }

    private static GetSignTaskListRes page(int number, int total, List<SignTaskInfo> items) {
        var result = new GetSignTaskListRes(); result.setListPageNo(number); result.setTotalCount(total);
        result.setListPageCount(2); result.setCountInPage(items.size()); result.setSignTasks(items); return result;
    }

    @ParameterizedTest @ValueSource(strings = {"task", "reference", "owner", "actor-company", "actor-id",
            "actor-type", "permission", "document", "document-file", "extra-document", "abolish"})
    void rejectsAnyTaskDetailThatDoesNotMatchTheOriginalContract(String condition) throws Exception {
        var f = new Fixture();
        switch (condition) {
            case "task" -> f.detail.setSignTaskId("other-task");
            case "reference" -> f.detail.setTransReferenceId("contract-12-v2");
            case "owner" -> f.detail.setInitiator(OpenId.getInstance("corp", "corp-99"));
            case "actor-company" -> f.detail.getActors().get(1).getActorInfo().setActorOpenId("corp-99");
            case "actor-id" -> f.detail.getActors().get(1).getActorInfo().setActorId("supplier");
            case "actor-type" -> f.detail.getActors().get(1).getActorInfo().setActorType("person");
            case "permission" -> f.detail.getActors().get(1).getActorInfo().setPermissions(List.of("view"));
            case "document" -> f.doc.setDocName("different.pdf");
            case "document-file" -> f.doc.setDocFileId(null);
            case "extra-document" -> f.detail.setDocs(List.of(f.doc, f.doc));
            case "abolish" -> f.detail.setOriginalSignTaskId("parent");
        }
        assertThatThrownBy(() -> f.gateway.findCreatedTask(f.command)).hasMessageContaining("与合同不一致");
        verify(f.client, never()).create(any());
    }

    @Test void aProviderReadFailureCannotBeMistakenForNoTask() throws Exception {
        var f = new Fixture(); var failure = new BaseRes<GetSignTaskListRes>(); failure.setCode("210002");
        when(f.client.getOwnerList(any())).thenReturn(failure);
        assertThatThrownBy(() -> f.gateway.findCreatedTask(f.command)).hasMessageContaining("失败");
        verify(f.client, never()).create(any());
    }

    @SuppressWarnings("unchecked") private static <T> BaseRes<T> success(T value) {
        BaseRes<T> result = mock(BaseRes.class);
        when(result.isSuccess()).thenReturn(true); when(result.getData()).thenReturn(value); return result;
    }

    private static class Fixture {
        final SignTaskClient client = mock(SignTaskClient.class);
        final SdkFadadaSigningGateway gateway;
        final FadadaProperties properties = new FadadaProperties();
        final FadadaSigningGateway.TaskRecoveryCommand command = new FadadaSigningGateway.TaskRecoveryCommand(
                "contract-12-v1", "corp-3", "supplier", "corp-4", "buyer", "合同.pdf");
        final GetSignTaskListRes list = new GetSignTaskListRes();
        final SignTaskInfo info = new SignTaskInfo();
        final SignTaskDetailRes detail = new SignTaskDetailRes();
        final SignTaskDocRes doc = new SignTaskDocRes();
        Fixture() throws Exception {
            properties.setAppId("app"); properties.setAppSecret("secret");
            properties.setServerUrl("https://example.test");
            gateway = new SdkFadadaSigningGateway(properties, mock(FadadaAccessTokenProvider.class));
            ReflectionTestUtils.setField(gateway, "signTaskClient", client);
            info.setSignTaskId("original"); info.setTransReferenceId("contract-12-v1");
            list.setTotalCount(1); list.setListPageNo(1); list.setListPageCount(1); list.setCountInPage(1); list.setSignTasks(List.of(info));
            detail.setSignTaskId("original"); detail.setTransReferenceId("contract-12-v1");
            detail.setInitiator(OpenId.getInstance("corp", "corp-3")); detail.setSignDocType("contract");
            detail.setActors(List.of(actor("supplier", "corp-3"), actor("buyer", "corp-4")));
            doc.setDocId("contract-doc"); doc.setDocName("合同.pdf"); doc.setDocFileId("original-file");
            detail.setDocs(List.of(doc));
            when(client.getOwnerList(any())).thenAnswer(call -> success(list));
            when(client.getDetail(any())).thenAnswer(call -> success(detail));
        }
        SignTaskDetailActor actor(String actorId, String corpId) {
            var actor = new SignTaskDetailActor(); var info = new SignTaskDetailActorInfo();
            info.setActorId(actorId); info.setActorType("corp"); info.setActorOpenId(corpId);
            info.setPermissions(List.of("sign")); actor.setActorInfo(info); return actor;
        }
    }
}
