# ============================================================
# Multi-stage Dockerfile for School Manager
#
# Stage 1 (node)   — build the React frontend
# Stage 2 (gradle) — build the Spring Boot fat JAR
# Stage 3 (jre)    — minimal runtime image
# ============================================================

# ---------- Stage 1: Frontend build ----------
FROM node:20-slim AS frontend-builder

# Install pnpm
RUN npm install -g pnpm

WORKDIR /app/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY frontend/ ./
RUN pnpm build


# ---------- Stage 2: Backend build ----------
FROM eclipse-temurin:21-jdk-jammy AS backend-builder

WORKDIR /app

# Copy Gradle wrapper and build files first (layer cache)
COPY backend/gradlew backend/gradlew.bat ./backend/
COPY backend/gradle/ ./backend/gradle/
COPY backend/settings.gradle backend/build.gradle ./backend/

# Pre-fetch Gradle dependencies
RUN cd backend && chmod +x gradlew && ./gradlew dependencies -q --no-daemon 2>/dev/null || true

# Copy source code
COPY backend/src/ ./backend/src/

# Copy the built frontend into Spring Boot's static resources directory
COPY --from=frontend-builder /app/frontend/dist/ ./backend/src/main/resources/static/

# Build fat JAR (skip the buildFrontend Gradle task — we already copied the dist above)
RUN cd backend && ./gradlew bootJar -x buildFrontend -x copyFrontend --no-daemon -q


# ---------- Stage 3: Runtime image ----------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy only the fat JAR
COPY --from=backend-builder /app/backend/build/libs/*.jar app.jar

# Runtime directories (mapped via volumes)
RUN mkdir -p /app/data /app/backups

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=local"]
