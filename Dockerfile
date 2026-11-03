FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S healthcare && adduser -S healthcare -G healthcare
WORKDIR /app
COPY --from=build --chown=healthcare:healthcare /workspace/target/appointment-service-0.0.1-SNAPSHOT.jar application.jar
USER healthcare
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
