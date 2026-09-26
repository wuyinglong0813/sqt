#!/usr/bin/env python3
"""Connect an existing local Jenkins container to this CentOS host and create release jobs."""
import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

PLUGINS = ["workflow-aggregator", "pipeline-model-definition", "git", "credentials-binding",
           "ssh-agent", "ssh-slaves", "junit", "maven-plugin", "timestamper", "plain-credentials"]
MARKER = "Managed by TradePass core release setup"

CONFIGURE = r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.slaves.*
import hudson.plugins.sshslaves.SSHLauncher
import hudson.plugins.sshslaves.verifiers.ManuallyProvidedKeyVerificationStrategy
import hudson.tasks.Maven.MavenInstallation
import hudson.plugins.git.*
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl

// TP_CONFIG
def j = Jenkins.get()
def marker = 'Managed by TradePass core release setup'
def domain = Domain.global()
def store = SystemCredentialsProvider.getInstance().getStore()
def existing = store.getCredentials(domain)
def roles = ['identity', 'business', 'gateway', 'all']
// Check name collisions and running jobs before making any changes.
roles.each { role ->
    def job = j.getItem('tradepass-publish-' + role)
    if (job && (!(job instanceof WorkflowJob) || job.description != marker || job.isBuilding())) {
        throw new IllegalStateException('Existing task is not owned by setup, or is running: ' + role)
    }
}
def oldNode = j.getNode('tradepass-ci')
if (oldNode && (oldNode.nodeDescription != marker || oldNode.toComputer().countBusy() > 0)) {
    throw new IllegalStateException('Existing tradepass-ci node is not owned by setup, or is busy')
}
def credentialIds = ['tradepass-ci-ssh', 'tradepass-core-ssh', 'tradepass-core-known-hosts']
if (cfg.gitToken) credentialIds.add('tradepass-git')
credentialIds.each { id ->
    def old = existing.find { it.id == id }
    if (old && old.description != marker) throw new IllegalStateException('Credential ID already in use: ' + id)
}
def put = { credential ->
    def old = existing.find { it.id == credential.id }
    boolean ok = old ? store.updateCredentials(domain, old, credential) : store.addCredentials(domain, credential)
    if (!ok) throw new IllegalStateException('Could not save credential: ' + credential.id)
}
put(new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, 'tradepass-ci-ssh', 'tradepass-ci',
    new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(cfg.ciKey), '', marker))
put(new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, 'tradepass-core-ssh', 'root',
    new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(cfg.deployKey), '', marker))
put(new FileCredentialsImpl(CredentialsScope.GLOBAL, 'tradepass-core-known-hosts', marker,
    'known_hosts', SecretBytes.fromBytes(cfg.knownHosts.getBytes('UTF-8'))))
if (cfg.gitToken) put(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL,
    'tradepass-git', marker, cfg.gitUser, cfg.gitToken))
def gitCredential = store.getCredentials(domain).find { it.id == 'tradepass-git' } ? 'tradepass-git' : ''

def launcher = new SSHLauncher(cfg.agentHost, 22, 'tradepass-ci-ssh', '-Xms64m -Xmx256m',
    '/usr/lib/jvm/java-21-openjdk/bin/java', '', '', 60, 3, 10,
    new ManuallyProvidedKeyVerificationStrategy(cfg.hostKey))
def node = new DumbSlave('tradepass-ci', '/opt/tradepass/jenkins-agent', launcher)
node.setNodeDescription(marker)
node.setNumExecutors(1)
node.setMode(Node.Mode.EXCLUSIVE)
node.setLabelString('tradepass-ci')
node.setRetentionStrategy(new RetentionStrategy.Always())
j.addNode(node)
def jdks = j.getJDKs().findAll { it.name != 'tradepass-jdk17' }
jdks.add(new JDK('tradepass-jdk17', '/usr/lib/jvm/java-17-openjdk'))
j.setJDKs(jdks)
def maven = j.getDescriptorByType(hudson.tasks.Maven.DescriptorImpl)
def installations = maven.getInstallations().findAll { it.name != 'tradepass-maven' }
installations.add(new MavenInstallation('tradepass-maven', '/usr/share/maven', []))
maven.setInstallations(installations as MavenInstallation[])
maven.save()
// Builds belong on the host agent, not the controller container.
j.setNumExecutors(0)
j.save()
roles.each { role ->
    def name = 'tradepass-publish-' + role
    def job = j.getItem(name) ?: j.createProject(WorkflowJob, name)
    def scm = new GitSCM([new UserRemoteConfig(cfg.repository, null, null, gitCredential)],
        [new BranchSpec('*/main')], false, [], null, null, [])
    def definition = new CpsScmFlowDefinition(scm, 'Jenkinsfile.core')
    definition.setLightweight(true)
    job.setDefinition(definition)
    job.setDescription(marker)
    job.addProperty(new DisableConcurrentBuildsJobProperty())
    job.addProperty(new ParametersDefinitionProperty([
        new ChoiceParameterDefinition('ACTION', ['status','deploy','build','restart','rollback','recover'] as String[], '选择发布、重启、回滚或检查'),
        new ChoiceParameterDefinition('SERVICE', ([role] + roles.findAll { it != role }) as String[], '服务'),
        new ChoiceParameterDefinition('IMAGE_DELIVERY', ['local','archive'] as String[], '同机选择 local'),
        new StringParameterDefinition('DEPLOY_HOST', '127.0.0.1'),
        new StringParameterDefinition('DEPLOY_USER', 'root'),
        new StringParameterDefinition('DEPLOY_PORT', '22'),
        new StringParameterDefinition('CORE_COMPOSE', '')
    ]))
    job.save()
}
node.toComputer().connect(false)
println('TP_SETUP_OK')
'''


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("Jenkins 本机接口发生重定向，请检查容器端口；没有转发凭据")


class Jenkins:
    def __init__(self, user, token):
        self.authorization = "Basic " + base64.b64encode((user + ":" + token).encode()).decode()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def script(self, script):
        request = urllib.request.Request("http://127.0.0.1:18080/scriptText",
            data=urllib.parse.urlencode({"script": script}).encode(),
            headers={"Authorization": self.authorization})
        try:
            with self.opener.open(request, timeout=30) as response:
                return response.read().decode()
        except urllib.error.HTTPError as error:
            raise RuntimeError("Jenkins 接口返回 HTTP " + str(error.code) + "；请使用管理员 API Token，并确认已完成初始化") from None


def run(*command, capture=False):
    result = subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def configure_script(config):
    encoded = base64.b64encode(json.dumps(config).encode()).decode()
    declaration = "def cfg = new groovy.json.JsonSlurper().parseText(new String('" + encoded + "'.decodeBase64(), 'UTF-8'))"
    return CONFIGURE.replace("// TP_CONFIG", declaration)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--container", default="tradepass-jenkins")
    args = parser.parse_args()
    if not args.install:
        print("执行 --install：复用现有 Jenkins；安装宿主 JDK 17/21、Maven、Git、Python；创建一个构建节点和四个发布任务。")
        print("创建专用 SSH 密钥，构建账号加入 Docker 组（拥有宿主机高权限）；凭据写入 Jenkins。")
        print("缺少插件时仅重启 Jenkins。不会触发应用发布；不会修改 MySQL、Nacos 或应用容器。")
        return
    if os.geteuid() != 0:
        parser.error("请在业务服务器使用 root 执行")
    info = json.loads(run("docker", "inspect", args.container, capture=True))[0]
    if not info["State"]["Running"]:
        raise RuntimeError("现有 Jenkins 容器没有运行")
    ports = info["NetworkSettings"].get("Ports", {}).get("8080/tcp") or []
    # An IPv4 wildcard binding also accepts requests to the local loopback address.
    # Keep API requests on loopback; accepting this binding does not change exposure.
    if not any(p["HostIp"] in ("127.0.0.1", "0.0.0.0") and p["HostPort"] == "18080" for p in ports):
        raise RuntimeError("脚本需要 Jenkins 的 8080 映射到本机 IPv4 18080 端口（127.0.0.1 或 0.0.0.0），请核对容器")
    user = input("Jenkins 管理员用户名 [admin]: ").strip() or "admin"
    token = getpass.getpass("Jenkins 管理员 API Token（不是初始解锁密码，不回显）: ").strip()
    if not token:
        raise RuntimeError("需要管理员 API Token")
    client = Jenkins(user, token)
    probe = '''def j = jenkins.model.Jenkins.get()
println(groovy.json.JsonOutput.toJson([plugins: j.pluginManager.plugins.findAll { it.active }.collect { it.shortName },
busy: j.computers.any { it.countBusy() > 0 } || !j.queue.isEmpty()]))'''
    state = json.loads(client.script(probe))
    if state["busy"]:
        raise RuntimeError("Jenkins 有正在执行或排队的任务，请等待完成后重试")
    git_user = input("GitHub 用户名 [wuyinglong0813]: ").strip() or "wuyinglong0813"
    git_token = getpass.getpass("GitHub 仓库读取 Token（不回显；已有 tradepass-git 凭据或公开仓库可留空）: ").strip()
    print("准备宿主构建节点；应用仍保持运行。", flush=True)
    run("bash", str(Path(__file__).with_name("setup-core-jenkins.sh")), "--agent-only")
    missing = [name for name in PLUGINS if name not in state["plugins"]]
    if missing:
        print("安装缺少的 Jenkins 插件：" + ", ".join(missing), flush=True)
        run("docker", "exec", args.container, "jenkins-plugin-cli", "--plugin-download-directory",
            "/var/jenkins_home/plugins", "--plugins", *missing)
        # Check again in case a job started while packages/plugins downloaded.
        if json.loads(client.script(probe))["busy"]:
            raise RuntimeError("插件已下载，但 Jenkins 新任务正在运行；等待完成后重跑脚本以加载插件")
        run("docker", "restart", args.container)
        deadline = time.monotonic() + 240
        while True:
            try:
                state = json.loads(client.script(probe))
                if all(name in state["plugins"] for name in PLUGINS):
                    break
            except (urllib.error.URLError, RuntimeError, ValueError):
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Jenkins 插件尚未就绪，请检查 docker logs tradepass-jenkins 后重跑")
            time.sleep(3)
    info = json.loads(run("docker", "inspect", args.container, capture=True))[0]
    extras = info["HostConfig"].get("ExtraHosts") or []
    if any(value.startswith("host.docker.internal:") for value in extras):
        agent_host = "host.docker.internal"
    else:
        gateways = {net["Gateway"] for net in info["NetworkSettings"]["Networks"].values() if net.get("Gateway")}
        if len(gateways) != 1:
            raise RuntimeError("无法确定 Jenkins 到宿主机的地址，请配置 host.docker.internal 映射")
        agent_host = gateways.pop()
    private = Path("/opt/tradepass/jenkins-private")
    config = {"agentHost": agent_host, "repository": "https://github.com/wuyinglong0813/sqt.git",
              "ciKey": (private / "ci").read_text(), "deployKey": (private / "deploy").read_text(),
              "knownHosts": (private / "known_hosts").read_text(), "gitUser": git_user, "gitToken": git_token,
              "hostKey": " ".join(Path("/etc/ssh/ssh_host_ed25519_key.pub").read_text().split()[:2])}
    result = client.script(configure_script(config))
    if result.strip() != "TP_SETUP_OK":
        # Groovy compile/runtime diagnostics can include the encoded credential payload.
        raise RuntimeError("Jenkins 配置未完成；未打印可能包含凭据的响应。请核对插件版本及同名任务、节点、凭据冲突")
    print("四个发布任务已创建，正在等待 tradepass-ci 节点上线。", flush=True)
    deadline = time.monotonic() + 180
    while client.script("println(jenkins.model.Jenkins.get().getComputer('tradepass-ci').isOnline())").strip() != "true":
        if time.monotonic() > deadline:
            raise RuntimeError("任务已创建，但构建节点未上线；在 Jenkins 的 tradepass-ci 节点日志检查 SSH 连接")
        time.sleep(3)
    print("构建节点已上线。任务：tradepass-publish-identity / business / gateway / all。")
    print("进入任务 → Build with Parameters：先用 ACTION=status 检查；发布选择 ACTION=deploy。")
    print("任务读取 Git main 分支的 Jenkinsfile.core，请先确保发布脚本已推送；尚未执行任何应用发布。")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit("已取消。可以重新运行接入脚本。")
    except Exception as error:
        # Never print request bodies or subprocess arguments containing credentials.
        raise SystemExit(str(error) if isinstance(error, RuntimeError) else "接入失败：" + type(error).__name__)
