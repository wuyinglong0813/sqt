package com.tradepass.module.contract.framework.config;

import com.tradepass.module.contract.framework.callback.FadadaCallbackProcessor;
import com.tradepass.module.contract.framework.callback.FadadaCallbackRecovery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CallbackInfrastructureTest {
    @Test void repeatedNotificationsReachTheExistingDurableClaimProcessor() {
        var processor = mock(FadadaCallbackProcessor.class);
        var registry = new SimpleMeterRegistry();
        var listener = CallbackMessagingConfiguration.callbackListener(processor, registry);
        MessageExt message = message("9007199254740993");
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, listener.consumeMessage(List.of(message), null));
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, listener.consumeMessage(List.of(message), null));
        verify(processor, times(2)).process(9007199254740993L);
        assertEquals(2, registry.counter("tradepass.callback.consume", "result", "handled").count());
    }

    @Test void malformedOrFailedMessagesRequestBrokerRedelivery() {
        var processor = mock(FadadaCallbackProcessor.class);
        var listener = CallbackMessagingConfiguration.callbackListener(processor, new SimpleMeterRegistry());
        for (String invalid : List.of("", "-1", "0", "{\"payload\":true}", "9999999999999999999")) {
            assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, listener.consumeMessage(List.of(message(invalid)), null));
        }
        verifyNoInteractions(processor);
        doThrow(new IllegalStateException("database unavailable")).when(processor).process(1L);
        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, listener.consumeMessage(List.of(message("1")), null));
    }

    @Test void failedPublicationReportsDeferredDeliveryWithoutInventingSuccess() throws Exception {
        var producer = mock(DefaultMQProducer.class);
        when(producer.send(any(Message.class))).thenThrow(new IllegalStateException("broker unavailable"));
        var registry = new SimpleMeterRegistry();
        var dispatcher = new CallbackMessagingConfiguration().callbackDispatcher(producer, registry, "callback-test");
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(71L));
        assertEquals(1, registry.counter("tradepass.callback.dispatch", "result", "deferred").count());
        verify(producer, times(1)).send(argThat((Message m) -> new String(m.getBody(), StandardCharsets.UTF_8).equals("71")));
    }

    @Test void scheduledJobUsesOriginalRecoveryAndPropagatesFailure() {
        var recovery = mock(FadadaCallbackRecovery.class);
        var registry = new SimpleMeterRegistry();
        var handler = new CallbackJobConfiguration.CallbackRecoveryJob(recovery, registry);
        handler.recover();
        verify(recovery).recover();
        doThrow(new IllegalStateException("database unavailable")).when(recovery).recover();
        assertThrows(IllegalStateException.class, handler::recover);
        assertEquals(1, registry.counter("tradepass.callback.recovery", "result", "failed").count());
    }

    @Test void executorRejectsAnEmptyOrWeakSharedTokenBeforeStarting() {
        assertThrows(IllegalStateException.class, () -> new CallbackJobConfiguration()
                .callbackJobExecutor("http://jobs:8080", "short", "", "", 9998, "/tmp/jobs"));
    }

    private static MessageExt message(String body) {
        var message = new MessageExt();
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
