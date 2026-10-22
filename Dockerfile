# Runtime container with Java 21 JRE
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create unprivileged system user for DevSecOps best practices
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

COPY target/financial-movements-1.0.0-SNAPSHOT.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
