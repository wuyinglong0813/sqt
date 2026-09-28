import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.remoting.protocol.RemotingSerializable;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.apache.rocketmq.remoting.protocol.route.QueueData;
import org.apache.rocketmq.remoting.protocol.route.TopicRouteData;
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;

/** Versioned resource contract; no message publishing, offset resets, or deletion. Java 8 compatible. */
public class ResourceAdmin {
    public static class Resource {
        public String id, topic, consumerGroup;
        public int readQueues, writeQueues;
    }
    public static class Plan {
        public int schema;
        public String cluster, brokerName, advertisedAddress, accessKey, secretKey;
        public List<Resource> resources;
    }
    private static class ValidationException extends RuntimeException {
        ValidationException(String message) { super(message); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new ValidationException(message);
    }
    private static boolean present(String value) { return value != null && !value.isEmpty(); }
    private static void validate(Plan plan) {
        require(plan != null && plan.schema == 1, "Unsupported resource manifest");
        require(present(plan.cluster) && present(plan.brokerName), "Broker identity is required");
        require(plan.resources != null && !plan.resources.isEmpty(), "Empty resource manifest");
        Set<String> ids = new HashSet<String>();
        Set<String> topics = new HashSet<String>();
        Set<String> groups = new HashSet<String>();
        for (Resource resource : plan.resources) {
            require(present(resource.id) && ids.add(resource.id), "Duplicate/missing resource id");
            require(resource.topic != null && resource.topic.matches("[A-Za-z0-9_-][A-Za-z0-9_%-]{0,126}")
                    && topics.add(resource.topic), "Invalid/duplicate topic");
            require(resource.consumerGroup != null && resource.consumerGroup.matches("[A-Za-z0-9_-][A-Za-z0-9_%-]{0,126}")
                    && groups.add(resource.consumerGroup), "Invalid/duplicate consumer group");
            require(resource.readQueues > 0 && resource.writeQueues > 0, "Queue counts must be positive");
        }
        require(present(plan.accessKey) == present(plan.secretKey), "Incomplete ACL credentials");
    }
    private static boolean topicValid(TopicConfig topic, Resource resource) {
        return topic != null && topic.getReadQueueNums() >= resource.readQueues
                && topic.getWriteQueueNums() >= resource.writeQueues
                && PermName.isReadable(topic.getPerm()) && PermName.isWriteable(topic.getPerm());
    }
    private static boolean groupValid(SubscriptionGroupConfig group) {
        return group != null && group.isConsumeEnable();
    }
    private static boolean routeValid(DefaultMQAdminExt admin, Plan plan, Resource resource) throws Exception {
        TopicRouteData route = admin.examineTopicRouteInfo(resource.topic);
        boolean broker = false, queues = false;
        if (route == null) return false;
        for (BrokerData data : route.getBrokerDatas()) {
            if (plan.brokerName.equals(data.getBrokerName()) && plan.cluster.equals(data.getCluster())
                    && data.getBrokerAddrs().containsKey(0L)
                    && (!present(plan.advertisedAddress) || plan.advertisedAddress.equals(data.getBrokerAddrs().get(0L)))) {
                broker = true;
            }
        }
        for (QueueData data : route.getQueueDatas()) {
            if (plan.brokerName.equals(data.getBrokerName()) && data.getReadQueueNums() >= resource.readQueues
                    && data.getWriteQueueNums() >= resource.writeQueues
                    && PermName.isReadable(data.getPerm()) && PermName.isWriteable(data.getPerm())) queues = true;
        }
        return broker && queues;
    }
    private static int execute(String[] args) throws Exception {
        require(args.length == 4, "Usage: ResourceAdmin audit|check|ensure MANIFEST|- NAMESERVER DIRECT_BROKER");
        String mode = args[0];
        require(Arrays.asList("audit", "check", "ensure").contains(mode), "Invalid mode");
        byte[] input;
        if ("-".equals(args[1])) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = System.in.read(buffer)) != -1) out.write(buffer, 0, length);
            input = out.toByteArray();
        } else input = Files.readAllBytes(Paths.get(args[1]));
        Plan plan = RemotingSerializable.fromJson(new String(input, StandardCharsets.UTF_8), Plan.class);
        validate(plan);
        DefaultMQAdminExt admin = present(plan.accessKey)
                ? new DefaultMQAdminExt(new AclClientRPCHook(new SessionCredentials(plan.accessKey, plan.secretKey)), 3000L)
                : new DefaultMQAdminExt(3000L);
        admin.setNamesrvAddr(args[2]);
        admin.setInstanceName("tradepass-resources-" + UUID.randomUUID());
        try {
            admin.start();
            // Always address the intended broker directly. A host-loopback advertised route is not
            // reachable from an init container's separate network namespace.
            String broker = args[3];
            Properties config = admin.getBrokerConfig(broker);
            require(plan.cluster.equals(config.getProperty("brokerClusterName"))
                    && plan.brokerName.equals(config.getProperty("brokerName"))
                    && "0".equals(config.getProperty("brokerId")), "Broker identity mismatch; no changes made");
            require(!Boolean.parseBoolean(config.getProperty("autoCreateTopicEnable"))
                    && !Boolean.parseBoolean(config.getProperty("autoCreateSubscriptionGroup")),
                    "Automatic resource creation must be disabled; resource inventory is not enforced");
            Map<String, TopicConfig> topics = admin.getAllTopicConfig(broker, 3000L).getTopicConfigTable();
            Map<String, SubscriptionGroupConfig> groups = admin.getAllSubscriptionGroup(broker, 3000L).getSubscriptionGroupTable();
            System.out.println("Broker: " + plan.cluster + "/" + plan.brokerName);
            if ("audit".equals(mode)) {
                System.out.println("ALL_TOPICS " + new TreeSet<String>(topics.keySet()));
                System.out.println("ALL_CONSUMER_GROUPS " + new TreeSet<String>(groups.keySet()));
            }
            // Check all existing resources before making any changes; never overwrite drift.
            boolean drift = false;
            for (Resource resource : plan.resources) {
                if ((topics.containsKey(resource.topic) && !topicValid(topics.get(resource.topic), resource))
                        || (groups.containsKey(resource.consumerGroup) && !groupValid(groups.get(resource.consumerGroup)))) {
                    System.out.println("DRIFT " + resource.id + " (queue/permission/consumeEnable); manual review required");
                    drift = true;
                }
            }
            if (drift) return 2;
            if ("ensure".equals(mode)) {
                for (Resource resource : plan.resources) {
                    if (!topics.containsKey(resource.topic)) {
                        TopicConfig topic = new TopicConfig(resource.topic, resource.readQueues, resource.writeQueues,
                                PermName.PERM_READ | PermName.PERM_WRITE);
                        admin.createAndUpdateTopicConfig(broker, topic);
                        System.out.println("CREATED topic " + resource.topic);
                    }
                    if (!groups.containsKey(resource.consumerGroup)) {
                        SubscriptionGroupConfig group = new SubscriptionGroupConfig();
                        group.setGroupName(resource.consumerGroup);
                        admin.createAndUpdateSubscriptionGroupConfig(broker, group);
                        System.out.println("CREATED consumer group " + resource.consumerGroup);
                    }
                }
            }
            // Verify persisted broker config AND NameServer routes, even after successful writes.
            boolean allReady = false;
            int attempts = "ensure".equals(mode) ? 12 : 1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                topics = admin.getAllTopicConfig(broker, 3000L).getTopicConfigTable();
                groups = admin.getAllSubscriptionGroup(broker, 3000L).getSubscriptionGroupTable();
                allReady = true;
                List<String> report = new ArrayList<String>();
                for (Resource resource : plan.resources) {
                    boolean route = false;
                    try { route = routeValid(admin, plan, resource); }
                    catch (Exception unavailable) { /* reported below; never accept an exception as success */ }
                    boolean topic = topicValid(topics.get(resource.topic), resource);
                    boolean group = groupValid(groups.get(resource.consumerGroup));
                    allReady &= topic && group && route;
                    report.add(resource.id + " topic=" + resource.topic + ":" + topic
                            + " consumerGroup=" + resource.consumerGroup + ":" + group + " route=" + route);
                }
                if (allReady || attempt == attempts - 1) {
                    for (String line : report) System.out.println(line);
                    break;
                }
                Thread.sleep(1000);
            }
            System.out.println(allReady ? "MQ_RESOURCES_OK" : "MQ_RESOURCES_NOT_READY");
            return allReady ? 0 : 2;
        } finally { admin.shutdown(); }
    }
    public static void main(String[] args) {
        int code;
        try { code = execute(args); }
        catch (Exception failure) {
            // Provider exceptions can contain configuration; don't dump credentials or payloads.
            System.err.println("MQ resource operation failed: " + failure.getClass().getSimpleName());
            if (failure instanceof ValidationException) System.out.println("MQ_RESOURCE_ERROR " + failure.getMessage());
            code = 1;
        }
        System.exit(code);
    }
}
