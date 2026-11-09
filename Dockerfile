FROM node:24-bookworm-slim AS frontend
WORKDIR /work/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /work/backend
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /work/frontend/dist ./src/main/resources/static
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 app && mkdir /app/photos && chown app:app /app/photos
COPY --from=backend /work/backend/target/check-collect-0.1.0.jar ./app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
