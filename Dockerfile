FROM node:22-alpine AS frontend
WORKDIR /build
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /build/dist ./src/main/resources/static
RUN mvn -B verify

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S -g 1000 copilot && adduser -S -u 1000 copilot -G copilot
WORKDIR /app
COPY --from=backend /build/target/copilot-0.1.0.jar app.jar
USER copilot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
