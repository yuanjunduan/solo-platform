# syntax=docker/dockerfile:1.7
# ============================================================================
#  Solo 后端镜像（M7 / S5）
#
#  两阶段：build 阶段用 Maven 出 fat jar，runtime 阶段只带 JRE（镜像更小、
#  运行容器里没有编译工具链）。改代码只会重跑第二阶段之后的构建，依赖层可缓存。
#
#  离线/受限网络构建：docker build --build-arg MAVEN_ARGS="-o -s /build/settings.xml" .
# ============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
ARG MAVEN_ARGS=""

# 先只拷 pom：依赖层单独成层，改 Java 代码不会重新下载依赖
COPY pom.xml ./
RUN mvn -B -q ${MAVEN_ARGS} dependency:go-offline

COPY src ./src
RUN mvn -B -q ${MAVEN_ARGS} -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
ARG APP_UID=10001

# curl 只用于容器健康检查；tzdata 让日志与国标时间戳固定为东八区
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && groupadd -g ${APP_UID} solo \
    && useradd -m -u ${APP_UID} -g solo solo

ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=pg \
    LOG_DIR=/var/log/solo \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -Duser.timezone=Asia/Shanghai"

WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
RUN mkdir -p /var/log/solo && chown -R solo:solo /app /var/log/solo

# 非 root 运行：容器逃逸时拿到的权限最小
USER solo

EXPOSE 8080
EXPOSE 5060/udp
EXPOSE 5060/tcp

# 存活探针不碰依赖（DB/Redis 抖动时不应重启进程）；就绪判定交给编排的 /api/health/ready
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
