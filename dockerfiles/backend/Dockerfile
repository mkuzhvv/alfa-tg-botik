FROM eclipse-temurin:21-jdk-jammy@sha256:742daacb9316be4b6fc60258f6e4ac2a11882f6c597373c92290a57bfad0d2e0 AS build
WORKDIR /workspace

COPY backend/mvnw ./mvnw
COPY backend/.mvn ./.mvn
COPY backend/pom.xml ./pom.xml
COPY backend/src/main ./src/main

RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress package

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build --chown=app:app /workspace/target/alfa-tg-botik.jar ./app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
