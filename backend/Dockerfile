FROM eclipse-temurin:21.0.11_10-jre-alpine-3.23 AS runtime-base
WORKDIR /app

RUN set -eu; \
    original_repositories="$(cat /etc/apk/repositories)"; \
    installed=0; \
    for mirror in https://mirrors.huaweicloud.com/alpine https://mirrors.aliyun.com/alpine https://dl-cdn.alpinelinux.org/alpine; do \
        printf '%s\n' "$original_repositories" | sed -E "s#https?://[^/]+/alpine#$mirror#g" > /etc/apk/repositories; \
        echo "[fonts] Source: $mirror; attempt limit: 180s"; \
        if timeout 180 apk --timeout 20 --progress add --no-cache fontconfig font-noto-cjk; then \
            installed=1; break; \
        fi; \
        echo "[fonts] Download/install failed or timed out; trying next source"; \
    done; \
    test "$installed" = 1; \
    fc-match --format '%{family}\n' 'Noto Sans CJK SC' | grep -F 'Noto Sans CJK SC'

RUN addgroup -S asset \
    && adduser -S asset -G asset \
    && mkdir -p /app/data/asset-images \
    && chown -R asset:asset /app/data

FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY docker-maven-settings.xml docker-maven-build.sh /build/
COPY pom.xml ./
RUN sh /build/docker-maven-build.sh dependencies

COPY src/main ./src/main
RUN sh /build/docker-maven-build.sh package

FROM runtime-base
COPY --from=build --chown=asset:asset /workspace/target/*.jar /app/app.jar

USER asset
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
