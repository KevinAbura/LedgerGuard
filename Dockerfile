FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
RUN groupadd --system ledgerguard && useradd --system --gid ledgerguard ledgerguard
WORKDIR /app
COPY --from=build /workspace/target/ledgerguard-*.jar /app/ledgerguard.jar
USER ledgerguard
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/ledgerguard.jar"]
