#!/bin/sh
# Run inside the official RocketMQ image. Do not use mqadmin's unreliable exit code.
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$(mktemp -d /tmp/tradepass-mq-classes.XXXXXX)
trap 'rm -rf -- "$build"' EXIT HUP INT TERM
javac -J-Xmx64m -cp "$ROCKETMQ_HOME/lib/*" -d "$build" "$base/ResourceAdmin.java"
java -Xms16m -Xmx64m -XX:MaxDirectMemorySize=32m -cp "$build:$ROCKETMQ_HOME/lib/*" ResourceAdmin "$@"
