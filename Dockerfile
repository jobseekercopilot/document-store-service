FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B --no-transfer-progress -DskipTests clean package

FROM eclipse-temurin:17-jre-alpine
# Upgrade the OpenSSL runtime packages to the CVE-2026-14456 fixed build.
RUN apk add --no-cache --upgrade \
    libcrypto3=3.5.8-r0 \
    libssl3=3.5.8-r0 \
    expat=2.8.4-r0 \
    openssl=3.5.8-r0

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN apk add --no-cache curl
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "app.jar"]
