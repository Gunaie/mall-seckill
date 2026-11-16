# syntax=docker/dockerfile:1.7
# ============================================================
# mall-seckill 多阶段镜像：Maven 构建 -> JRE 精简运行
# 构建: docker build -t mall-seckill-app:1.0.0 .
# ============================================================

# ---------- 阶段 1：Maven 构建 fat jar ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 阿里云公共仓库镜像加速国内依赖下载
COPY docker/maven/settings.xml /root/.m2/settings.xml

# 先拷贝 pom 预拉依赖，依赖不变时可复用 Docker 层缓存；
# BuildKit cache mount 持久化本地仓库，二次构建无需重新下载
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B dependency:go-offline || true

# 再拷贝源码构建（源码变更不会使依赖层缓存失效）
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B clean package -DskipTests \
    && cp target/mall-seckill.jar /build/mall-seckill.jar

# ---------- 阶段 2：JRE 运行时 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 非 root 用户运行
RUN groupadd -r mall && useradd -r -g mall mall

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai -Djava.security.egd=file:/dev/./urandom"

COPY --from=builder --chown=mall:mall /build/mall-seckill.jar app.jar

# Sentinel 默认写到 ${user.home}/logs/csp；Nacos 快照默认写到 ${user.home}/nacos/config；
# RocketMQ 客户端日志容器内固定写到 ${user.home}/logs/rocketmqlogs（logback 配置写死 user.home）
# 非 root 用户需要可写目录，统一在 /app/logs 与 /home/mall/logs 下创建并授权
RUN mkdir -p /app/logs/sentinel /app/logs/nacos /home/mall/logs/rocketmqlogs \
    && chown -R mall:mall /app/logs /home/mall

USER mall
EXPOSE 8080

# TCP 层存活探针（temurin 基础镜像不带 curl/wget），HTTP 健康由 Nginx/Prometheus 验证
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
