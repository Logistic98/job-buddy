ARG DOCKER_REGISTRY=docker.io
FROM ${DOCKER_REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS build
ARG MAVEN_REPO_URL=https://repo.maven.apache.org/maven2
ENV MAVEN_REPO_URL=${MAVEN_REPO_URL}
WORKDIR /app
COPY pom.xml settings.docker.xml ./
COPY src ./src
RUN mvn -q -s settings.docker.xml -DskipTests package

FROM ${DOCKER_REGISTRY}/library/eclipse-temurin:17-jre-ubi9-minimal
WORKDIR /app
RUN groupadd --system app \
    && useradd --system --gid app --no-create-home app \
    && mkdir -p /workspace \
    && chown app:app /workspace
COPY --from=build --chown=app:app /app/target/agent-backend-1.0.0.jar app.jar
EXPOSE 8080
USER app
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
