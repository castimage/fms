# ============================================================
# fms_backend/Dockerfile —— FMS 后端多阶段构建镜像
# ------------------------------------------------------------
# 模块说明：
#   - 阶段一 build：Maven 构建，产物 /build/target/fms-new.jar
#     （pom.xml 中 finalName=fms-new，Java 25 / Spring Boot 4.1）
#   - 阶段二 runtime：eclipse-temurin:25-jre 瘦身运行层，
#     非 root 用户运行，仅含 fat jar 与 curl（供 HEALTHCHECK）
#   - 健康检查：/internal/health（该路径经网关 GatewaySignatureFilter
#     免签名放行，供 compose service_healthy 依赖条件使用）
# ============================================================

# ---------- 阶段一：Maven 构建 ----------
# 依赖下载经阿里云镜像（/root/.m2/settings.xml 显式 mirror）：直连 Maven
# Central 在公网慢且握手易中断（2026-08 实测 Remote host terminated the
# handshake），aliyun 镜像国内稳定，一劳永逸；
# 3.9.16 为官方镜像矩阵中确证存在的 temurin-25 tag（3.9.9 无此 tag）
FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /build

# 阿里云 maven mirror（mirrorOf=* 全量镜像；aliyun 定期同步 Maven Central）
RUN mkdir -p /root/.m2 && printf '%s' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>aliyunmaven</id><mirrorOf>*</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' \
    > /root/.m2/settings.xml

# 先只拷 pom.xml 拉依赖，利用 Docker 层缓存：改源码不重下依赖
COPY pom.xml ./
RUN mvn -B dependency:go-offline

# 严禁 COPY config/ 目录！fms_backend/config/application.yaml 含明文
# DB 密码且 server.port=8083（容器内必须跑 8081），任何情况下不得入镜像
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package  # 跳过测试编译（测试本地跑；服务器源码缺部分测试适配）
# 产物确认：/build/target/fms-new.jar（pom.xml finalName=fms-new）

# ---------- 阶段二：运行时（JRE 25） ----------
FROM eclipse-temurin:25-jre

# curl 供 compose HEALTHCHECK 探测 /internal/health 使用
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行用户：网关（fms-rgw）镜像已占用 uid 10001，
# 后端避让改用 10002，防止跨容器 uid 冲突
RUN groupadd -r fms -g 10002 && useradd -r -u 10002 -g fms fms

WORKDIR /app
COPY --from=build /build/target/fms-new.jar /app/fms-new.jar

# 附件本地卷目录（fms-files 命名卷持久化；镜像内预建并 chown 给非 root 用户，
# 命名卷首挂时继承目录所有权，fms 用户可写
RUN mkdir -p /app/data/files && chown -R fms:fms /app/data/files

EXPOSE 8081

# 健康检查：interval 5s / timeout 10s / retries 5 / start_period 30s，
# 该路径经 GatewaySignatureFilter 免签名放行
HEALTHCHECK --interval=5s --timeout=10s --retries=5 --start-period=30s \
    CMD curl -fsS http://127.0.0.1:8081/internal/health || exit 1

USER fms
ENTRYPOINT ["java", "-jar", "/app/fms-new.jar"]
