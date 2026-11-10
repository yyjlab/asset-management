FROM node:24.19.0-bookworm-slim AS build
WORKDIR /workspace

COPY docker-install-deps.sh /build/docker-install-deps.sh
RUN sh /build/docker-install-deps.sh pnpm
COPY package.json pnpm-lock.yaml .npmrc ./
RUN sh /build/docker-install-deps.sh dependencies

COPY . .
RUN pnpm build

FROM nginx:1.30.4-alpine3.24
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O - http://127.0.0.1/healthz | grep -q ok || exit 1

