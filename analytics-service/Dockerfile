FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml ./pom.xml
COPY id-generator-service/pom.xml   id-generator-service/pom.xml
COPY auth-service/pom.xml            auth-service/pom.xml
COPY tenant-service/pom.xml          tenant-service/pom.xml
COPY url-service/pom.xml             url-service/pom.xml
COPY redirect-service/pom.xml        redirect-service/pom.xml
COPY analytics-service/pom.xml       analytics-service/pom.xml
COPY api-gateway/pom.xml             api-gateway/pom.xml
RUN mvn dependency:go-offline -pl analytics-service -am -B -q
COPY analytics-service/src analytics-service/src
RUN mvn package -pl analytics-service -DskipTests -B -q

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=builder /app/analytics-service/target/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
