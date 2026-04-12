FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app
# Copy all POMs first for optimal layer caching
# (Maven validates all declared modules exist before resolving deps)
COPY pom.xml ./pom.xml
COPY id-generator-service/pom.xml   id-generator-service/pom.xml
COPY auth-service/pom.xml            auth-service/pom.xml
COPY tenant-service/pom.xml          tenant-service/pom.xml
COPY url-service/pom.xml             url-service/pom.xml
COPY redirect-service/pom.xml        redirect-service/pom.xml
COPY analytics-service/pom.xml       analytics-service/pom.xml
COPY api-gateway/pom.xml             api-gateway/pom.xml
# Resolve only this module's dependencies (cached until any pom.xml changes)
RUN mvn dependency:go-offline -pl id-generator-service -am -B -q
# Copy source and build
COPY id-generator-service/src id-generator-service/src
RUN mvn package -pl id-generator-service -DskipTests -B -q

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=builder /app/id-generator-service/target/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
