# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
# Cache dependencies first for faster rebuilds
RUN ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

# ---- Run stage (alpine = ~half the size, still arm64-ready) ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /app/target/backend-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
# Render/Oracle pass their port via SERVER_PORT (Spring maps it to server.port).
ENV SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "app.jar"]
