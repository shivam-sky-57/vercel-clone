# =======================================================
# Stage 1: Build the Reverse Proxy Application
# =======================================================
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# =======================================================
# Stage 2: Runtime Image (Lightweight JRE 21)
# =======================================================
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=builder /build/target/s3-reverse-proxy-*.jar /app/s3-reverse-proxy.jar

EXPOSE 8000

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/s3-reverse-proxy.jar"]
