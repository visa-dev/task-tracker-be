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

# Install curl
RUN apk add --no-cache curl

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring

# Create logs directory and give ownership to spring
RUN mkdir -p /app/logs && chown -R spring:spring /app

# Copy application
COPY --from=build /app/target/*.jar /app/app.jar

# Switch to non-root user
USER spring:spring

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java","-jar","/app/app.jar"]