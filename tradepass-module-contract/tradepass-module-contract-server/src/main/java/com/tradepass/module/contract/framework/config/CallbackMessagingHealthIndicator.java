package com.tradepass.module.contract.framework.config;

import org.apache.rocketmq.client.impl.MQClientAPIImpl;
import org.apache.rocketmq.common.constant.PermName;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Read-only readiness probe against the application's effective MQ configuration. */
final class CallbackMessagingHealthIndicator implements HealthIndicator {
    private final MQClientAPIImpl client;
    private final String topic;
    private final String group;

    CallbackMessagingHealthIndicator(MQClientAPIImpl client, String topic, String group) {
        this.client = client;
        this.topic = topic;
        this.group = group;
    }

    @Override
    public Health health() {
        try {
            var route = client.getTopicRouteInfoFromNameServer(topic, 1000);
            if (route == null || route.getQueueDatas() == null || route.getQueueDatas().isEmpty()
                    || route.getBrokerDatas() == null || route.getBrokerDatas().isEmpty()) {
                return down("topic route missing");
            }
            for (var queues : route.getQueueDatas()) {
                if (queues.getReadQueueNums() < 1 || queues.getWriteQueueNums() < 1
                        || !PermName.isReadable(queues.getPerm()) || !PermName.isWriteable(queues.getPerm())) {
                    return down("topic route not readable/writable");
                }
                var broker = route.getBrokerDatas().stream()
                        .filter(item -> item.getBrokerName().equals(queues.getBrokerName()))
                        .findFirst().orElse(null);
                if (broker == null || broker.getBrokerAddrs().get(0L) == null) return down("master broker missing");
                String address = broker.getBrokerAddrs().get(0L);
                // Connect to the advertised address, exactly as the producer/consumer do.
                var actual = client.getTopicConfig(address, topic, 1000);
                if (actual == null || actual.getReadQueueNums() < 1 || actual.getWriteQueueNums() < 1
                        || !PermName.isReadable(actual.getPerm()) || !PermName.isWriteable(actual.getPerm())) {
                    return down("broker topic not readable/writable");
                }
                var subscription = client.getSubscriptionGroupConfig(address, group, 1000);
                if (subscription == null || !subscription.isConsumeEnable()) return down("consumer group missing/disabled");
            }
            return Health.up().build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return down("probe interrupted");
        } catch (Exception unavailable) {
            return down("broker/route/resource unavailable");
        }
    }

    private Health down(String reason) {
        return Health.down().withDetail("reason", reason).build();
    }
}
