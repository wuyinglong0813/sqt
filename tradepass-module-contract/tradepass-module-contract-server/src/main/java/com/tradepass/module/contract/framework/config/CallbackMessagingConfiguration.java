package com.tradepass.module.contract.framework.config;

import com.tradepass.module.contract.framework.callback.CallbackDispatcher;
import com.tradepass.module.contract.framework.callback.FadadaCallbackProcessor;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.RPCHook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "tradepass.messaging.rocketmq.enabled", havingValue = "true")
public class CallbackMessagingConfiguration {
    @Bean(destroyMethod = "shutdown")
    DefaultMQProducer callbackProducer(
            @Value("${tradepass.messaging.rocketmq.name-server}") String server,
            @Value("${tradepass.messaging.rocketmq.producer-group:tradepass-module-contract/tradepass-module-contract-server-callback-producer}") String group,
            @Value("${tradepass.messaging.rocketmq.access-key:}") String accessKey,
            @Value("${tradepass.messaging.rocketmq.secret-key:}") String secretKey) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(group, credentials(accessKey, secretKey));
        producer.setNamesrvAddr(server);
        producer.setSendMsgTimeout(3000);
        producer.setRetryTimesWhenSendFailed(0);
        producer.setRetryTimesWhenSendAsyncFailed(0);
        producer.start();
        return producer;
    }

    @Bean CallbackDispatcher callbackDispatcher(DefaultMQProducer producer, MeterRegistry metrics,
            @Value("${tradepass.messaging.rocketmq.callback-topic:tradepass-callback-events}") String topic) {
        return id -> {
            if (id == null || id <= 0) throw new IllegalArgumentException("Invalid persisted callback ID");
            // No provider payload or credentials enter MQ. The database remains the durable event store.
            Message message = new Message(topic, "CALLBACK_READY", id.toString(), id.toString().getBytes(StandardCharsets.UTF_8));
            try {
                if (producer.send(message).getSendStatus() != SendStatus.SEND_OK) {
                    throw new IllegalStateException("Callback delivery was not acknowledged");
                }
                metrics.counter("tradepass.callback.dispatch", "result", "sent").increment();
            } catch (InterruptedException interrupted) {
                metrics.counter("tradepass.callback.dispatch", "result", "deferred").increment();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Callback dispatch interrupted; durable recovery remains available", interrupted);
            } catch (Exception failure) {
                metrics.counter("tradepass.callback.dispatch", "result", "deferred").increment();
                throw new IllegalStateException("Callback dispatch deferred to durable recovery", failure);
            }
        };
    }

    @Bean(destroyMethod = "shutdown")
    DefaultMQPushConsumer callbackConsumer(FadadaCallbackProcessor processor, MeterRegistry metrics,
            @Value("${tradepass.messaging.rocketmq.name-server}") String server,
            @Value("${tradepass.messaging.rocketmq.callback-topic:tradepass-callback-events}") String topic,
            @Value("${tradepass.messaging.rocketmq.consumer-group:tradepass-module-contract/tradepass-module-contract-server-callback-consumer}") String group,
            @Value("${tradepass.messaging.rocketmq.access-key:}") String accessKey,
            @Value("${tradepass.messaging.rocketmq.secret-key:}") String secretKey) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group, credentials(accessKey, secretKey));
        consumer.setNamesrvAddr(server);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(8);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setMaxReconsumeTimes(8);
        consumer.subscribe(topic, "CALLBACK_READY");
        consumer.registerMessageListener(callbackListener(processor, metrics));
        consumer.start();
        return consumer;
    }

    static org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently callbackListener(
            FadadaCallbackProcessor processor, MeterRegistry metrics) {
        return (messages, context) -> {
            try {
                for (var message : messages) {
                    String value = new String(message.getBody(), StandardCharsets.UTF_8);
                    if (!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid callback message");
                    processor.process(Long.valueOf(value));
                    metrics.counter("tradepass.callback.consume", "result", "handled").increment();
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (RuntimeException failure) {
                metrics.counter("tradepass.callback.consume", "result", "retry").increment();
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        };
    }

    private static RPCHook credentials(String key, String secret) {
        if (key.isBlank() && secret.isBlank()) return null;
        if (key.isBlank() || secret.isBlank()) throw new IllegalStateException("RocketMQ credentials must be configured together");
        return new AclClientRPCHook(new SessionCredentials(key, secret));
    }
}
