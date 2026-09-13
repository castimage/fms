# fms_rgw (fms-gateway) 多阶段 Docker 镜像
#
# Stage 1 builder : rust:1.94-slim 执行 cargo build --release
# Stage 2 runtime : debian:bookworm-slim 最小化
# 非 root 用户（uid 10001，便于 K8s 安全上下文）
# EXPOSE 8443（TLS）
# VOLUME /app/static（静态资源挂载，镜像不内置前端构建产物）
# HEALTHCHECK 探测 /healthz（TLS/明文降级双探测）
#
# 构建: docker build -t fms_rgw .
# 运行: docker run -d -p 8443:8443 \
#         -v $PWD/static:/app/static \
#         -v $PWD/certs:/app/certs \
#         -v $PWD/logs:/app/logs \
#         fms_rgw

# Stage 1: builder
FROM rust:1.94-slim AS builder

# rustls(ring) 需要 C 工具链（gcc/make/perl）
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# 先拷贝清单构建依赖层（Cargo 层缓存：仅源码变更时不会重编全部依赖）
COPY Cargo.toml ./
RUN mkdir -p src \
    && echo 'fn main() {}' > src/main.rs \
    && cargo build --release \
    && rm -rf src

# 拷贝真实源码并增量构建
COPY src ./src
RUN cargo build --release

# Stage 2: runtime
FROM debian:bookworm-slim

# curl 用于 HEALTHCHECK；ca-certificates 保证 https 出站可用
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行用户（uid/gid 固定 10001）
RUN groupadd -r fms_rgw --gid 10001 \
    && useradd -r -g fms_rgw -u 10001 -d /app -s /usr/sbin/nologin fms_rgw

WORKDIR /app

COPY --from=builder /build/target/release/fms_rgw /usr/local/bin/fms_rgw
COPY config /app/config

# 目录布局（相对路径配置均以 /app 为工作目录解析）:
# config/fms_rgw.yaml 配置（模板无密钥：启动前经环境变量 RGW_JWT_SECRET/RGW_SIGN_SECRET
# 注入强密钥，或挂载覆盖为 fms_rgw.local.yaml，空/占位/过短密钥启动失败）
# static/ 前端 SPA 构建产物（VOLUME 挂载）
# logs/ 访问日志 JSONL
# certs/ TLS 证书（VOLUME 挂载；镜像配置 allow_insecure_fallback=false，
# 缺证书容器启动失败，生产必须挂载证书）
RUN mkdir -p /app/static /app/logs /app/certs \
    && chown -R fms_rgw:fms_rgw /app

VOLUME ["/app/static", "/app/logs", "/app/certs"]

EXPOSE 8443

USER fms_rgw

# TLS 证书存在则 https 自检（生产语义；缺证书默认拒绝启动，此处 http 探测仅为开发降级配置兜底）
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsSk https://127.0.0.1:8443/healthz || curl -fsS http://127.0.0.1:8443/healthz || exit 1

# 配置默认加载路径 config/fms_rgw.yaml（相对工作目录 /app）
ENTRYPOINT ["fms_rgw"]
