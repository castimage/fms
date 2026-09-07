# ============================================================================
# FMS 前端构建镜像（方案 A：一次性构建产物交付镜像）
# ----------------------------------------------------------------------------
# 本镜像仅供 compose 服务 fms-web-build 一次性运行（restart: "no"）使用：
#   1. 阶段一在 Node 22 LTS Alpine 环境中执行 Vite 构建，产出 /app/dist
#   2. 阶段二为极简 Alpine 交付镜像，ENTRYPOINT 执行 cp -r 将构建产物
#      拷入命名卷 fms-static（容器内挂载点 /static）
# 生产运行期无独立 web 容器：dist 由网关 fms-rgw 以 /app/static 只读挂载
# 对外提供静态资源服务（方案 A），API 走同源相对路径 /api，免 CORS。
# ============================================================================

# ----------------------------------------------------------------------------
# 阶段一：构建阶段（Node.js 22 LTS 精简镜像）
# ----------------------------------------------------------------------------
FROM node:22-alpine AS build

# 工作目录
WORKDIR /app

# 先拷贝依赖清单，利用 Docker 层缓存：package.json / package-lock.json
# 未变更时跳过依赖安装步骤，加速重复构建
COPY package*.json ./

# 有 package-lock.json 时用 npm ci（严格按锁文件安装，保证可复现），
# 否则退化为 npm install（package.json 无特殊 Node 版本要求，22 LTS 兼容）
RUN sh -c 'if [ -f package-lock.json ]; then npm ci; else npm install; fi'

# 拷贝全部源码后执行构建。
# vite.config.ts 的 build 脚本为 vue-tsc -b && vite build，产物输出到 /app/dist；
# Vite 构建时 API 走同源相对路径 /api，无需额外配置（由网关反向代理到后端）
COPY . .
RUN npm run build

# ----------------------------------------------------------------------------
# 阶段二：交付阶段（Alpine 3.20 精简镜像，仅执行拷贝动作，不运行任何服务）
# ----------------------------------------------------------------------------
FROM alpine:3.20

WORKDIR /app

# 仅保留构建产物，镜像中不含源码与 node_modules，减小交付体积
COPY --from=build /app/dist ./dist

# 声明卷挂载点：compose 中命名卷 fms-static 挂载于此；
# 网关 fms-rgw 同时以只读方式挂载同一命名卷至 /app/static，
# 实现"构建一次、双端可见"（本容器写入、网关只读服务）
VOLUME ["/static"]

# 一次性拷贝：cp -r /app/dist/. /static/
# 使用 /app/dist/. 结尾点号，确保拷贝的是目录内容（含隐藏文件）
# 而非目录本身；容器正常退出（exit 0）即代表产物已持久化到命名卷，
# compose 据此以 service_completed_successfully 驱动 fms-rgw 启动
ENTRYPOINT ["cp", "-r", "/app/dist/.", "/static/"]
