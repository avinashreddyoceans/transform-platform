# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# Copy gradle wrapper and build scripts first (layer cache)
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# Copy module build files
COPY platform-common/build.gradle.kts platform-common/
COPY platform-core/build.gradle.kts    platform-core/
COPY platform-api/build.gradle.kts     platform-api/
COPY platform-pipeline/build.gradle.kts platform-pipeline/
COPY platform-scheduler/build.gradle.kts platform-scheduler/

# Resolve dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Copy all sources
COPY platform-common/src platform-common/src
COPY platform-core/src    platform-core/src
COPY platform-api/src     platform-api/src
COPY platform-pipeline/src platform-pipeline/src
COPY platform-scheduler/src platform-scheduler/src

# Build the API jar
RUN ./gradlew :platform-api:bootJar --no-daemon -x test

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /workspace/platform-api/build/libs/platform-api-*.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
