FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/failover-worker.jar /app/worker.jar
ENTRYPOINT ["java", "-jar", "/app/worker.jar"]
