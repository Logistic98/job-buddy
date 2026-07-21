ARG DOCKER_REGISTRY=docker.io
FROM ${DOCKER_REGISTRY}/library/node:22-alpine AS build
ARG NPM_CONFIG_REGISTRY=https://registry.npmjs.org
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM ${DOCKER_REGISTRY}/library/nginx:1.27-alpine
ENV BACKEND_UPSTREAM=agent-backend:8080
COPY --from=build /app/dist /usr/share/nginx/html
COPY default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q --spider http://127.0.0.1/ || exit 1
