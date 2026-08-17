# =======================================================
# Stage 1: Build the Spring Boot Application
# =======================================================
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# =======================================================
# Stage 2: AWS ECS Fargate Runtime (JRE 21 + Node.js + Git)
# =======================================================
FROM eclipse-temurin:21-jre-jammy

# Install Git, Node.js (v20 LTS), npm, and curl
RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    curl \
    ca-certificates \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Create workspace directory for repo builds
RUN mkdir -p /app/output

# Copy built Spring Boot JAR from builder stage
COPY --from=builder /build/target/build-server-*.jar /app/build-server.jar

# AWS ECS Container Entrypoint
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/build-server.jar"]
