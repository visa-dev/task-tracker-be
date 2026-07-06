# --- Build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Run stage ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# curl needed for the HEALTHCHECK below (alpine's base image doesn't include it)
RUN apk add --no-cache curl

# Run as a non-root user - standard hardening for any production container.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Used by Docker itself, docker-compose, and can be wired into an EC2/ALB target group
# health check pointing at the same /actuator/health path.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
