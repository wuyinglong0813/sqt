package com.tradepass.module.contract.framework.config;

import org.apache.rocketmq.client.impl.MQClientAPIImpl;
import org.apache.rocketmq.remoting.protocol.route.*;
import org.apache.rocketmq.remoting.protocol.statictopic.TopicConfigAndQueueMapping;
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import java.util.HashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CallbackMessagingHealthIndicatorTest {
    @Test void missingRouteOrUnreachableAdvertisedBrokerIsNotReady() throws Exception {
        var api = mock(MQClientAPIImpl.class);
        var health = new CallbackMessagingHealthIndicator(api, "configured-topic", "configured-group");
        assertEquals(Status.DOWN, health.health().getStatus());
        when(api.getTopicRouteInfoFromNameServer("configured-topic", 1000)).thenReturn(route());
        when(api.getTopicConfig("127.0.0.1:10911", "configured-topic", 1000)).thenThrow(new RuntimeException("private details"));
        var result = health.health();
        assertEquals(Status.DOWN, result.getStatus());
        assertFalse(result.toString().contains("private details"));
    }

    @Test void existingTopicCannotHideMissingOrDisabledConsumerGroup() throws Exception {
        var api = mock(MQClientAPIImpl.class);
        when(api.getTopicRouteInfoFromNameServer("configured-topic", 1000)).thenReturn(route());
        var topic = new TopicConfigAndQueueMapping();
        topic.setReadQueueNums(4); topic.setWriteQueueNums(4); topic.setPerm(6);
        when(api.getTopicConfig("127.0.0.1:10911", "configured-topic", 1000)).thenReturn(topic);
        var health = new CallbackMessagingHealthIndicator(api, "configured-topic", "configured-group");
        assertEquals(Status.DOWN, health.health().getStatus());
        var group = new SubscriptionGroupConfig();
        group.setConsumeEnable(false);
        when(api.getSubscriptionGroupConfig("127.0.0.1:10911", "configured-group", 1000)).thenReturn(group);
        assertEquals(Status.DOWN, health.health().getStatus());
        group.setConsumeEnable(true);
        assertEquals(Status.UP, health.health().getStatus());
        topic.setPerm(4);
        assertEquals(Status.DOWN, health.health().getStatus());
    }

    private TopicRouteData route() {
        var queue = new QueueData();
        queue.setBrokerName("broker"); queue.setReadQueueNums(4); queue.setWriteQueueNums(4); queue.setPerm(6);
        var broker = new BrokerData();
        broker.setBrokerName("broker"); broker.setBrokerAddrs(new HashMap<>(java.util.Map.of(0L, "127.0.0.1:10911")));
        var route = new TopicRouteData();
        route.setQueueDatas(List.of(queue)); route.setBrokerDatas(List.of(broker));
        return route;
    }
}
