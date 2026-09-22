pipeline {
    agent { label 'tradepass-ci' }
    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
    }
    tools {
        jdk 'tradepass-jdk17'
        maven 'tradepass-maven'
    }
    parameters {
        choice(name: 'ACTION', choices: ['verify', 'publish', 'deploy', 'rollback'], description: 'verify: 回归及构建；publish: 推送镜像；deploy: 发布测试环境；rollback: 恢复测试环境上一版本')
        string(name: 'IMAGE_PREFIX', defaultValue: '', description: '新后端仓库的镜像前缀（GHCR/ACR/Harbor），发布前填写，必须小写')
        string(name: 'DEPLOY_HOST', defaultValue: '', description: '测试服务器 IP 或域名，不带协议')
        string(name: 'DEPLOY_USER', defaultValue: 'tradepass-deploy', description: '服务器部署账号')
        string(name: 'DEPLOY_PORT', defaultValue: '22', description: '服务器 SSH 端口')
        string(name: 'DEPLOY_ROOT', defaultValue: '/opt/tradepass/staging', description: '已准备好 .env 的服务器部署目录')
        string(name: 'ROLLBACK_RELEASE', defaultValue: '', description: '留空使用服务器 previous 记录，或填写已发布的 COMMIT-BUILD_NUMBER')
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                checkout scm
                script {
                    env.IMAGE_TAG = "${env.GIT_COMMIT}-${env.BUILD_NUMBER}"
                    env.IMAGE_PREFIX = params.IMAGE_PREFIX.trim() ?: 'local/tradepass'
                    if (params.ACTION != 'verify' && (env.CHANGE_ID || !(env.BRANCH_NAME in ['main', 'master']))) {
                        error('发布凭据只允许 Multibranch Pipeline 的 main/master 分支使用')
                    }
                    if (params.ACTION in ['publish', 'deploy']) {
                        if (!params.IMAGE_PREFIX.trim() || params.IMAGE_PREFIX.contains('replace')) {
                            error('先配置真实的镜像仓库 IMAGE_PREFIX')
                        }
                    }
                    if (params.ACTION in ['deploy', 'rollback']) {
                        if (!params.DEPLOY_HOST.trim()) { error('先填写测试服务器 DEPLOY_HOST') }
                    }
                }
            }
        }
        stage('Business and release regression') {
            when { expression { params.ACTION != 'rollback' } }
            steps { sh 'bash scripts/ci/verify.sh' }
        }
        stage('Build six service images') {
            when { expression { params.ACTION != 'rollback' } }
            steps { sh 'python3 scripts/ci/release.py build' }
        }
        stage('Publish immutable release') {
            when { expression { params.ACTION in ['publish', 'deploy'] } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'tradepass-registry-push', usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_TOKEN')]) {
                    sh '''#!/usr/bin/env bash
set -euo pipefail
set +x
export DOCKER_CONFIG
DOCKER_CONFIG="$(mktemp -d)"
trap 'docker logout "${IMAGE_PREFIX%%/*}" >/dev/null 2>&1 || true; rm -rf "$DOCKER_CONFIG"' EXIT
printf '%s' "$REGISTRY_TOKEN" | docker login "${IMAGE_PREFIX%%/*}" --username "$REGISTRY_USER" --password-stdin
python3 scripts/ci/release.py publish
'''
                }
                archiveArtifacts(artifacts: 'dist/release/**', fingerprint: true)
            }
        }
        stage('Deploy or roll back staging') {
            when { expression { params.ACTION in ['deploy', 'rollback'] } }
            steps {
                sshagent(credentials: ['tradepass-staging-ssh']) {
                    withCredentials([file(credentialsId: 'tradepass-staging-known-hosts', variable: 'KNOWN_HOSTS_FILE')]) {
                        sh '''#!/usr/bin/env bash
set -euo pipefail
args=("$ACTION" --host "$DEPLOY_HOST" --user "$DEPLOY_USER" --port "$DEPLOY_PORT" \
  --root "$DEPLOY_ROOT" --known-hosts "$KNOWN_HOSTS_FILE")
if [[ -n "$ROLLBACK_RELEASE" ]]; then args+=(--release-id "$ROLLBACK_RELEASE"); fi
python3 scripts/cd/ssh_release.py "${args[@]}"
'''
                    }
                }
            }
        }
    }
    post {
        always {
            junit(testResults: 'deploy/integration-tests/target/surefire-reports/TEST-*.xml,**/target/surefire-reports/TEST-*.xml,**/target/failsafe-reports/TEST-*.xml', allowEmptyResults: true)
            archiveArtifacts(artifacts: 'dist/ci/**,deploy/coverage/target/site/jacoco-aggregate/**,deploy/smoke-tests/target/process-logs/**', allowEmptyArchive: true)
        }
    }
}
