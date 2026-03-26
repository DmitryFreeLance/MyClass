FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/max-bot-1.0.0.jar /app/app.jar
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
