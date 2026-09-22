# 交易履约服务 镜像。构建上下文必须是仓库根目录（对齐 yudao：Dockerfile 放在模块内）。
# 先执行: mvn -pl tradepass-module-trade/tradepass-module-trade-server -am -DskipTests package
# 再执行: docker build -f tradepass-module-trade/tradepass-module-trade-server/Dockerfile -t tradepass-trade:local .
FROM apache/skywalking-java-agent:9.5.0-java8 AS skywalking
FROM eclipse-temurin:17-jdk AS artifact
COPY tradepass-module-trade/tradepass-module-trade-server/target/tradepass-module-trade-server-0.1.0-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:17-jdk
WORKDIR /app
RUN command -v curl
COPY --from=artifact /app.jar /app/app.jar
COPY --from=skywalking /skywalking/agent /opt/skywalking/agent
COPY entrypoint.sh /app/entrypoint.sh
COPY scripts/server/arthas.sh /app/arthas.sh
USER 10001:10001
EXPOSE 8080 8081
ENV SERVER_PORT=8080 MANAGEMENT_PORT=8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 CMD curl --fail --silent --max-time 3 "http://127.0.0.1:${MANAGEMENT_PORT:-8081}/actuator/health/readiness" > /dev/null || exit 1
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
