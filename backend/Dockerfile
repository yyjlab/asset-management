FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:21.0.11_10-jre-alpine-3.23
WORKDIR /app

RUN sed -i 's#dl-cdn.alpinelinux.org#mirrors.aliyun.com#g' /etc/apk/repositories \
    && apk add --no-cache fontconfig font-noto-cjk \
    && addgroup -S asset \
    && adduser -S asset -G asset \
    && mkdir -p /app/data/asset-images \
    && chown -R asset:asset /app/data
COPY --from=build --chown=asset:asset /workspace/target/*.jar /app/app.jar

USER asset
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
