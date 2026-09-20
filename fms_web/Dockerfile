# FMS 前端的构建与交付镜像，产物经命名卷交付
# 本镜像仅供 compose 服务 fms-web-build 一次性运行，restart 为 "no"
# 阶段一在 Node 22 lts 的 Alpine 环境执行 Vite 构建，产出 /app/dist
# 阶段二为极简 Alpine 交付镜像，入口命令把构建产物拷入命名卷，容器内挂载点为 /static
# 生产运行期无独立 web 容器，产物由网关以只读挂载对外提供静态资源，
# api 走同源相对路径 /api，免 cors

# 阶段一，构建层，Node.js 22 lts 精简镜像
FROM node:22-alpine AS build

WORKDIR /app

# 先拷贝依赖清单，清单未变更时跳过依赖安装步骤，加速重复构建
COPY package*.json ./

# 有 package-lock.json 时用 npm ci 严格按锁文件安装，保证可复现，
# 否则退化为 npm install，package.json 无特殊 Node 版本要求，22 lts 兼容
RUN sh -c 'if [ -f package-lock.json ]; then npm ci; else npm install; fi'

# 拷贝全部源码后执行构建
# 构建脚本为 vue-tsc -b && vite build，产物输出到 /app/dist
# 构建期 api 走同源相对路径 /api，由网关反向代理到后端，无需额外配置
COPY . .
RUN npm run build

# 阶段二，交付层，Alpine 3.20 精简镜像，仅执行拷贝动作，不运行任何服务
FROM alpine:3.20

WORKDIR /app

# 仅保留构建产物，镜像中不含源码与 node_modules，减小交付体积
COPY --from=build /app/dist ./dist

# 声明卷挂载点，compose 中命名卷挂载于此，网关同时以只读方式挂载同一命名卷，
# 实现构建一次双端可见，本容器写入，网关只读服务
VOLUME ["/static"]

# 一次性拷贝，源路径以 /. 结尾，确保拷贝的是目录内容与隐藏文件而非目录本身
# 容器正常退出即代表产物已持久化到命名卷，compose 据此驱动网关启动
ENTRYPOINT ["cp", "-r", "/app/dist/.", "/static/"]
