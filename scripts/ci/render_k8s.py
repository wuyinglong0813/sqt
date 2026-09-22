#!/usr/bin/env python3
"""Render staging Kubernetes resources from the same digest-pinned release used by Compose."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from release import ROLES, validate_release

def render(data, namespace="tradepass-staging", datacenter=2):
    validate_release(data)
    if not re.fullmatch(r"tradepass-staging(?:-[a-z0-9-]+)?", namespace) or len(namespace) > 63:
        raise ValueError("This initial deployment bundle is restricted to staging namespaces")
    if not 0 <= datacenter <= 31: raise ValueError("Datacenter ID must be from 0 to 31")
    common = {"SERVER_PORT": "8080", "MANAGEMENT_PORT": "8081", "MANAGEMENT_ADDRESS": "0.0.0.0",
              "SERVER_SHUTDOWN": "graceful", "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE": "45s",
              "TRADEPASS_IDS_DATACENTER_ID": str(datacenter), "TRADEPASS_DEV_ENABLED": "false",
              "TRADEPASS_DEMO_DATA_ENABLED": "false", "TRADEPASS_EXPERIENCE_TEST_ACCOUNTS_ENABLED": "false",
              "SEATA_SERVER_ADDR": "seata:8091", "SPRING_PROFILES_ACTIVE": "observability",
              "ROCKETMQ_NAME_SERVER": "rocketmq-namesrv:9876", "XXL_JOB_ADMIN_ADDRESSES": "http://xxl-job-admin:8080/xxl-job-admin",
              "TRADEPASS_STORAGE_ENABLED": "false", "TRADEPASS_STORAGE_REQUIRED": "false", "TRADEPASS_REDIS_ENABLED": "false"}
    common.update({"TRADEPASS_" + role.upper() + "_URL": "http://" + role + ":8080" for role in ROLES if role != "gateway"})
    config_name = "tradepass-runtime-" + hashlib.sha256(json.dumps(common, sort_keys=True).encode()).hexdigest()[:12]
    items = [{"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": config_name, "namespace": namespace}, "data": common},
             {"apiVersion": "v1", "kind": "ServiceAccount", "metadata": {"name": "tradepass-runtime", "namespace": namespace}, "automountServiceAccountToken": False}]
    for index, role in enumerate(ROLES, 1):
        labels = {"app.kubernetes.io/name": "tradepass", "app.kubernetes.io/component": role}
        env = [{"name": "JAVA_OPTS", "value": "-Xms64m -Xmx" + ("256m" if role == "gateway" else "384m") + " -XX:ActiveProcessorCount=2"}]
        env += [{"name": "SW_AGENT_NAME", "value": "tradepass-" + role}]
        env_from = [{"configMapRef": {"name": config_name}}]
        if role != "gateway":
            env += [{"name": "TRADEPASS_IDS_WORKER_BASE", "value": str((index - 1) * 6)},
                    {"name": "TRADEPASS_POD_NAME", "valueFrom": {"fieldRef": {"fieldPath": "metadata.name"}}}]
            env_from += [{"secretRef": {"name": "tradepass-internal"}}]
            env_from += [{"secretRef": {"name": "tradepass-" + role}}]
            if role == "contract":
                env += [{"name": "SPRING_PROFILES_ACTIVE", "value": "observability,messaging,jobs"}]
        container = {"name": role, "image": data["images"][role], "imagePullPolicy": "IfNotPresent", "env": env, "envFrom": env_from,
                     "ports": [{"name": "http", "containerPort": 8080}, {"name": "management", "containerPort": 8081}],
                     "resources": {"requests": {"cpu": "200m", "memory": "256Mi"}, "limits": {"cpu": "2", "memory": "512Mi" if role == "gateway" else "1Gi"}},
                     "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True, "capabilities": {"drop": ["ALL"]}},
                     "volumeMounts": [{"name": "tmp", "mountPath": "/tmp"}],
                     "startupProbe": {"httpGet": {"path": "/actuator/health/liveness", "port": "management"}, "periodSeconds": 5, "failureThreshold": 60, "timeoutSeconds": 3},
                     "livenessProbe": {"httpGet": {"path": "/actuator/health/liveness", "port": "management"}, "periodSeconds": 15, "failureThreshold": 3, "timeoutSeconds": 3},
                     "readinessProbe": {"httpGet": {"path": "/actuator/health/readiness", "port": "management"}, "periodSeconds": 5, "failureThreshold": 3, "timeoutSeconds": 3}}
        items.append({"apiVersion": "apps/v1", "kind": "Deployment" if role == "gateway" else "StatefulSet", "metadata": {"name": role, "namespace": namespace, "labels": labels},
                      "spec": {"replicas": 1, "revisionHistoryLimit": 5, "progressDeadlineSeconds": 420, "strategy": {"type": "Recreate"},
                               "selector": {"matchLabels": labels}, "template": {"metadata": {"labels": labels, "annotations": {
                                   "tradepass.io/revision": data["revision"], "prometheus.io/scrape": "true", "prometheus.io/port": "8081", "prometheus.io/path": "/actuator/prometheus"}},
                                   "spec": {"serviceAccountName": "tradepass-runtime", "automountServiceAccountToken": False,
                                            "imagePullSecrets": [{"name": "tradepass-registry"}], "terminationGracePeriodSeconds": 60,
                                            "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001, "fsGroup": 10001, "seccompProfile": {"type": "RuntimeDefault"}},
                                            "containers": [container], "volumes": [{"name": "tmp", "emptyDir": {"sizeLimit": "256Mi"}}]}}}})
        if role != "gateway":
            stateful = items[-1]["spec"]
            stateful.pop("progressDeadlineSeconds")
            stateful.pop("strategy")
            stateful.update({"serviceName": role + "-pods", "podManagementPolicy": "Parallel", "updateStrategy": {"type": "RollingUpdate"}})
            if role == "contract": container["ports"].append({"name": "jobs", "containerPort": 9998})
            items.append({"apiVersion": "v1", "kind": "Service", "metadata": {"name": role + "-pods", "namespace": namespace},
                          "spec": {"clusterIP": "None", "selector": labels, "ports": [{"name": "http", "port": 8080, "targetPort": "http"}]}})
        items.append({"apiVersion": "v1", "kind": "Service", "metadata": {"name": role, "namespace": namespace, "labels": labels},
                      "spec": {"type": "ClusterIP", "selector": labels, "ports": [{"name": "http", "port": 8080, "targetPort": "http"}, {"name": "management", "port": 8081, "targetPort": "management"}]}})
    items.append({"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy", "metadata": {"name": "tradepass-private-ingress", "namespace": namespace},
                  "spec": {"podSelector": {"matchLabels": {"app.kubernetes.io/name": "tradepass"}}, "policyTypes": ["Ingress"],
                           "ingress": [{"from": [{"podSelector": {"matchLabels": {"app.kubernetes.io/name": "tradepass"}}}], "ports": [{"protocol": "TCP", "port": 8080}]},
                                       {"from": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "monitoring"}}}], "ports": [{"protocol": "TCP", "port": 8081}]}]}})
    items.append({"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy", "metadata": {"name": "tradepass-job-ingress", "namespace": namespace},
                  "spec": {"podSelector": {"matchLabels": {"app.kubernetes.io/component": "contract"}}, "policyTypes": ["Ingress"],
                           "ingress": [{"from": [{"podSelector": {"matchLabels": {"app.kubernetes.io/component": "xxl-job-admin"}}}],
                                        "ports": [{"protocol": "TCP", "port": 9998}]}]}})
    items.append({"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy", "metadata": {"name": "tradepass-gateway-ingress", "namespace": namespace},
                  "spec": {"podSelector": {"matchLabels": {"app.kubernetes.io/component": "gateway"}}, "policyTypes": ["Ingress"],
                           "ingress": [{"from": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "ingress-system"}}}], "ports": [{"protocol": "TCP", "port": 8080}]}]}})
    return {"apiVersion": "v1", "kind": "List", "items": items}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--namespace", default="tradepass-staging")
    parser.add_argument("--datacenter-id", type=int, default=2)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(render(json.loads(args.release.read_text()), args.namespace, args.datacenter_id), indent=2) + "\n")

if __name__ == "__main__":
    main()
