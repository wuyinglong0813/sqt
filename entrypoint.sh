#!/bin/sh
set -eu
if [ "${TRADEPASS_TRACING_ENABLED:-false}" = true ]; then
  : "${SW_AGENT_NAME:?Set a service-specific SkyWalking name}"
  : "${SW_AGENT_COLLECTOR_BACKEND_SERVICES:?Set the private OAP endpoint}"
  export SW_LOGGING_OUTPUT=CONSOLE
  agent="/opt/skywalking/agent/skywalking-agent.jar"
  if [ -f /data/skywalking/skywalking-agent/skywalking-agent.jar ]; then
    agent="/data/skywalking/skywalking-agent/skywalking-agent.jar"
  fi
  set -- -javaagent:"$agent"
else
  set --
fi
# JAVA_OPTS is controlled by the deployment operator, never by request data.
exec java -Duser.home=/tmp ${JAVA_OPTS:-} "$@" -jar /app/app.jar
