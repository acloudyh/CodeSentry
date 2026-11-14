FROM node:22.22-alpine AS builder

WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . ./
RUN npm run build

FROM nginx:1.27-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/security-headers.conf /etc/nginx/codesentry-security-headers.conf
COPY --from=builder /workspace/dist /usr/share/nginx/html

EXPOSE 443
