FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY certs/russian-trusted-root-ca.crt /usr/local/share/ca-certificates/russian-trusted-root-ca.crt
COPY certs/russian-trusted-sub-ca.crt /usr/local/share/ca-certificates/russian-trusted-sub-ca.crt
RUN update-ca-certificates \
    && keytool -importcert -noprompt -cacerts -storepass changeit \
         -alias russian-trusted-root-ca \
         -file /usr/local/share/ca-certificates/russian-trusted-root-ca.crt \
    && keytool -importcert -noprompt -cacerts -storepass changeit \
         -alias russian-trusted-sub-ca \
         -file /usr/local/share/ca-certificates/russian-trusted-sub-ca.crt

COPY --from=build /app/target/max-bot-1.0.0.jar /app/app.jar
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
