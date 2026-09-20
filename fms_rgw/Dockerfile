# fms_rgw 的多阶段构建镜像，compose 服务名为 fms-gateway
# 阶段一 builder 用 rust:1.94-slim 执行 cargo build --release
# 阶段二 runtime 用 debian:bookworm-slim 最小化，非 root 用户运行，uid 与 gid 固定 10001
# 对外暴露 8443 走 tls，静态资源目录 /app/static 以卷挂载，镜像不内置前端构建产物
# 健康检查探 /healthz，证书齐备时走 https，缺证书的降级配置下回退明文探测
# 本地构建用 docker build -t fms_rgw .，运行参数由 compose 承担

# 阶段一，构建层
FROM rust:1.94-slim AS builder

# rustls 的 ring 后端需要 C 工具链，即 gcc 与 make 与 perl
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# 先拷贝清单并以空壳主函数预构建，依赖编译结果留在本层，
# 后续源码变更只重编业务 crate，不必重编全部依赖
COPY Cargo.toml ./
RUN mkdir -p src \
    && echo 'fn main() {}' > src/main.rs \
    && cargo build --release \
    && rm -rf src

# 拷贝真实源码并增量构建
COPY src ./src
RUN cargo build --release

# 阶段二，运行层
FROM debian:bookworm-slim

# curl 供健康检查使用，ca-certificates 保证 https 出站可用
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行用户，uid 与 gid 固定 10001
RUN groupadd -r fms_rgw --gid 10001 \
    && useradd -r -g fms_rgw -u 10001 -d /app -s /usr/sbin/nologin fms_rgw

WORKDIR /app

COPY --from=builder /build/target/release/fms_rgw /usr/local/bin/fms_rgw
COPY config /app/config

# 目录布局，配置项里的相对路径均以工作目录 /app 解析
# config/fms_rgw.yaml 为配置模板且不含密钥，启动前经环境变量 FMS_RGW_JWT_SECRET 与
# FMS_RGW_SIGN_SECRET 注入强密钥，或挂载覆盖该文件，空值与占位值与过短密钥一律拒绝启动
# static 存前端 spa 构建产物，以卷挂载
# logs 存访问日志，jsonl 格式
# certs 存 tls 证书，以卷挂载，镜像配置 allow_insecure_fallback=false，
# 缺证书时容器启动即失败，生产必须挂载证书
RUN mkdir -p /app/static /app/logs /app/certs \
    && chown -R fms_rgw:fms_rgw /app

VOLUME ["/app/static", "/app/logs", "/app/certs"]

EXPOSE 8443

USER fms_rgw

# 证书齐备时自检 https，缺证书时按配置拒绝启动，此处的明文探测仅兜底开发用的降级配置
# 显式 ua 标识自检来源，默认 curl 版本号 ua 无法与外部探测区分，访问日志里语义不明
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsSk -A fms-rgw-healthcheck https://127.0.0.1:8443/healthz \
        || curl -fsS -A fms-rgw-healthcheck http://127.0.0.1:8443/healthz || exit 1

# 配置默认加载路径为 config/fms_rgw.yaml，相对工作目录 /app
ENTRYPOINT ["fms_rgw"]
