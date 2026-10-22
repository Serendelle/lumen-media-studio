# syntax=docker/dockerfile:1
# Build from the repository root. No local .env, data, or build output enters the context.
FROM node:24-bookworm-slim AS frontend-build
ENV NODE_OPTIONS=--max-old-space-size=2048
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
ENV MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m"
WORKDIR /build/backend
COPY backend/pom.xml ./
COPY backend/src/ ./src/
COPY backend/ofd-worker/ ./ofd-worker/
# Release packaging only; run the documented test suite before publishing an image.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.test.skip=true package
RUN --mount=type=cache,target=/root/.m2 mvn -f ofd-worker/pom.xml -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-noble AS java-runtime

# Trixie supplies the real ImageMagick 7 `magick` command and Chromium without Snap.
# The host may be Ubuntu 24.04; a container does not need to use the host distribution.
FROM debian:trixie-slim AS api
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    LANG=C.UTF-8 \
    HOME=/tmp/lumen \
    XDG_CACHE_HOME=/tmp/lumen/cache \
    APP_DATA_DIR=/data \
    APP_FRONTEND_DIST=/opt/lumen/no-frontend \
    OFD_WORKER_JAR=/opt/lumen/ofd-worker.jar \
    FFMPEG_PATH=/usr/bin/ffmpeg \
    FFPROBE_PATH=/usr/bin/ffprobe \
    EXIFTOOL_PATH=/usr/bin/exiftool \
    MAGICK_PATH=/usr/bin/magick \
    GHOSTSCRIPT_PATH=/usr/bin/gs \
    SOFFICE_PATH=/usr/bin/soffice \
    CHROMIUM_PATH=/usr/local/bin/lumen-chromium
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates curl ffmpeg imagemagick libmagickcore-7.q16-10-extra ghostscript libimage-exiftool-perl \
       libreoffice-writer libreoffice-calc chromium fonts-noto-cjk fonts-dejavu-core \
       fontconfig libfreetype6 libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 lumen \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /tmp/lumen lumen \
    && install -d -o 10001 -g 10001 /data /opt/lumen
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
COPY deploy/imagemagick-policy.xml /etc/ImageMagick-7/policy.xml
COPY --chmod=755 deploy/lumen-chromium /usr/local/bin/lumen-chromium
COPY --from=backend-build --chown=10001:10001 /build/backend/target/video-compressor-backend-1.0.0.jar /opt/lumen/app.jar
COPY --from=backend-build --chown=10001:10001 /build/backend/ofd-worker/target/lumen-ofd-worker.jar /opt/lumen/ofd-worker.jar
COPY --from=backend-build --chown=10001:10001 /build/backend/ofd-worker/target/lib/ /opt/lumen/lib/
WORKDIR /opt/lumen
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/opt/lumen/app.jar"]

FROM nginx:stable-alpine AS web
COPY --from=frontend-build --chown=101:101 /build/frontend/dist /usr/share/nginx/html
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY deploy/nginx/snippets/ /etc/nginx/snippets/
USER 101:101
EXPOSE 8080 8443
# Avoid the stock entrypoint modifying configuration in a read-only container.
ENTRYPOINT ["nginx", "-g", "daemon off;"]
