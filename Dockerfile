# Production Runtime Container
FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /app

# Create a non-root system user for security compliance
RUN groupadd -r appgroup && useradd -r -g appgroup -u 1001 appuser

# Copy the pre-built application JAR
COPY target/haproxy-0.0.1-SNAPSHOT.jar app.jar

USER appuser

# Expose HTTP service port
EXPOSE 8081

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
