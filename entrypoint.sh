#!/bin/sh
set -eu
if [ "${TRADEPASS_TRACING_ENABLED:-false}" = true ]; then
  : "${SW_AGENT_NAME:?Set a service-specific SkyWalking name}"
  : "${SW_AGENT_COLLECTOR_BACKEND_SERVICES:?Set the private OAP endpoint}"
  export SW_LOGGING_OUTPUT=CONSOLE
  set -- -javaagent:/opt/skywalking/agent/skywalking-agent.jar
else
  set --
fi
# JAVA_OPTS is controlled by the deployment operator, never by request data.
exec java -Duser.home=/tmp ${JAVA_OPTS:-} "$@" -jar /app/app.jar
