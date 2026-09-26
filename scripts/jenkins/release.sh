#!/usr/bin/env bash
# Run in a checked-out backend repository on the build host (JDK 17, Maven, Docker).
set -euo pipefail
set +x
TP_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$TP_ROOT"
TP_SERVICE=${1:?Specify identity, business, gateway or all}
TP_ACTION=${2:-status}
case "$TP_SERVICE" in
  identity) TP_MODULES=tradepass-module-identity/tradepass-module-identity-server ;;
  business) TP_MODULES=tradepass-business ;;
  gateway) TP_MODULES=tradepass-gateway ;;
  all) TP_MODULES=tradepass-module-identity/tradepass-module-identity-server,tradepass-business,tradepass-gateway ;;
  *) echo '服务只能是 identity、business、gateway、all' >&2; exit 2 ;;
esac
case "$TP_ACTION" in status|deploy|build|restart|rollback|recover) ;; *) echo '操作：status | deploy | build | restart | rollback | recover' >&2; exit 2 ;; esac
TP_BUNDLE=""
server_action() {
  local action=$1
  local options=("$action" --service "$TP_SERVICE")
  [[ -z ${CORE_COMPOSE:-} ]] || options+=(--compose "$CORE_COMPOSE")
  [[ -z $TP_BUNDLE ]] || options+=(--bundle "$TP_BUNDLE")
  if [[ -n ${DEPLOY_HOST:-} ]]; then
    : "${KNOWN_HOSTS_FILE:?Set the verified SSH known_hosts path}"
    [[ -z ${DEPLOY_SSH_KEY:-} ]] || options+=(--identity-file "$DEPLOY_SSH_KEY")
    python3 scripts/cd/core_ssh.py "${options[@]}" --host "$DEPLOY_HOST" \
      --user "${DEPLOY_USER:-root}" --port "${DEPLOY_PORT:-22}" --known-hosts "$KNOWN_HOSTS_FILE"
  else
    python3 scripts/cd/core_apply.py "${options[@]}"
  fi
}
if [[ $TP_ACTION == deploy ]]; then server_action status; fi
if [[ $TP_ACTION == deploy || $TP_ACTION == build ]]; then
  export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}
  export PATH="$JAVA_HOME/bin:$PATH"
  export MAVEN_OPTS=${MAVEN_OPTS:--Xmx768m -XX:ActiveProcessorCount=2}
  export JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:--Xmx512m -XX:ActiveProcessorCount=2}
  TP_REVISION=$(git rev-parse HEAD)
  TP_RELEASE="$TP_REVISION-${BUILD_NUMBER:-$(date +%s)}"
  TP_BUNDLE="$TP_ROOT/dist/core-releases/$TP_RELEASE/$TP_SERVICE"
  python3 -m unittest discover -s scripts/ci/tests -p test_core_release.py -v
  mvn -B -pl "$TP_MODULES" -am clean package
  python3 scripts/ci/core_release.py --service "$TP_SERVICE" --release "$TP_RELEASE" \
    --delivery "${IMAGE_DELIVERY:-local}" --output "$TP_BUNDLE"
fi
if [[ $TP_ACTION != build ]]; then server_action "$TP_ACTION"; fi
