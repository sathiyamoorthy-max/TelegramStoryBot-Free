FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package \
    && JAR_FILE="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | head -n 1)" \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" /tmp/app.jar

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar

EXPOSE 10000
ENTRYPOINT ["java","-jar","/app/app.jar"]
