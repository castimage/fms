# fms_backend 的多阶段构建镜像，Java 25 与 Spring Boot 4.1，pom.xml 中 finalName 为 fms-new
# 阶段一 build 用 Maven 构建，产物为 /build/target/fms-new.jar
# 阶段二 runtime 用 azul-zulu 的固定版本 tag 瘦身运行层，非 root 用户运行，镜像内只含 fat jar 与 BusyBox wget，在构建时校验 Java 最低版本
# 健康检查探 /internal/health，该路径经后端签名过滤器免签名放行，供 compose 的 service_healthy 依赖条件使用

# 运行时 Java 最低版本，25.0.5.1
ARG JAVA_MIN_VERSION=25.0.5.1

# 阶段一，Maven 构建层
# 依赖下载经阿里云镜像，直连 Maven Central 在公网慢且握手易中断，aliyun 镜像国内稳定
# 3.9.16 为官方镜像矩阵中带 temurin-25 tag 的版本，3.9.9 无此 tag
FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /build

# 阿里云 maven mirror，mirrorOf=* 为全量镜像，aliyun 定期同步 Maven Central
RUN mkdir -p /root/.m2 && printf '%s' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>aliyunmaven</id><mirrorOf>*</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' \
    > /root/.m2/settings.xml

# 先只拷 pom.xml 拉依赖，依赖层命中缓存后改源码不重下依赖
COPY pom.xml ./
RUN mvn -B dependency:go-offline

# 严禁 COPY config 目录，fms_backend/config/application.yaml 含明文数据库密码且 server.port 为 8083，
# 容器内必须跑 8081，该目录任何情况下不得入镜像
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package  # 跳过测试编译，测试本地跑，服务器源码缺部分测试适配

# 阶段二，运行时层，jre 25；25.38-25.0.5.1=
FROM azul-zulu:25.38-25.0.5.1-jre-alpine3.23
ARG JAVA_MIN_VERSION

# 校验运行时版本不低于修复阈值；低于阈值直接失败，避免静默构建出受影响镜像
RUN set -eux; \
    java_output="$(java -version 2>&1)"; \
    java_version="${java_output#*\"}"; \
    java_version="${java_version%%\"*}"; \
    [ -n "$java_version" ] || { echo "无法获取 Java 版本"; exit 1; }; \
    if ! awk -v actual="$java_version" -v minimum="$JAVA_MIN_VERSION" 'BEGIN { \
        split(actual, a, "."); split(minimum, m, "."); \
        for (i = 1; i <= 4; i++) { \
            if ((a[i] + 0) < (m[i] + 0)) exit 1; \
            if ((a[i] + 0) > (m[i] + 0)) exit 0; \
        } \
        exit 0; \
    }'; then \
        echo "Java $java_version 低于最低要求 $JAVA_MIN_VERSION"; \
        exit 1; \
    fi

# 非 root 运行用户，网关镜像已占用 uid 10001，后端避让改用 10002，防止跨容器 uid 冲突
RUN addgroup -S -g 10002 fms && adduser -S -D -H -u 10002 -G fms fms

WORKDIR /app
COPY --from=build /build/target/fms-new.jar /app/fms-new.jar

# 附件本地卷目录，命名卷持久化，镜像内预建并 chown 给非 root 用户，
# 命名卷首次挂载时继承目录所有权，fms 用户可写
RUN mkdir -p /app/data/files && chown -R fms:fms /app/data/files

EXPOSE 8081

# 健康检查 interval 5s，timeout 10s，retries 5，start_period 30s，探 /internal/health，
# 该路径经后端签名过滤器免签名放行，显式 ua 标识自检来源
HEALTHCHECK --interval=5s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -q -T 10 -O- -U fms-backend-healthcheck http://127.0.0.1:8081/internal/health || exit 1

USER fms
ENTRYPOINT ["java", "-jar", "/app/fms-new.jar"]
