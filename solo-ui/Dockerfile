# syntax=docker/dockerfile:1.7
# ============================================================================
#  Solo 控制台镜像（M7 / S5）：Node 构建 + nginx 静态托管 + /api 反代
#  前端只发相对路径请求（vite dev 时由 devServer 代理），因此生产环境把
#  /api、/open-api 反代到后端即可，不需要在前端塞绝对地址。
# ============================================================================
FROM node:20-alpine AS build
WORKDIR /app

# 锁文件单独成层：只改业务代码不重复装依赖
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.27-alpine AS runtime
ENV TZ=Asia/Shanghai

# 放到 templates 目录：官方入口脚本用 envsubst 渲染 ${SOLO_BACKEND} 后再启用
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

ENV SOLO_BACKEND=solo:8080

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
