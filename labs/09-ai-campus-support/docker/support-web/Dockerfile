FROM node:24.8.0-alpine AS build

WORKDIR /workspace
COPY support-web/package.json support-web/package-lock.json ./
RUN npm ci
COPY support-web/ ./
RUN npm run build

FROM nginx:1.29.1-alpine
COPY docker/support-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/ /usr/share/nginx/html/

EXPOSE 8080
