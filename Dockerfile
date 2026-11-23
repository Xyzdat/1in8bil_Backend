FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /app

COPY pom.xml ./
COPY --chmod=755 mvnw ./mvnw
COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/maven-wrapper.properties
COPY src/main ./src/main

RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-noble
WORKDIR /app

COPY --from=build /app/target/life-backend-*.jar ./app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
