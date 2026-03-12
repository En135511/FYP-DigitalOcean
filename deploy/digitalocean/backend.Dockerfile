FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /src
COPY . .

RUN chmod +x ./mvnw \
    && ./mvnw -f brailleai-output/pom.xml -DskipTests install \
    && ./mvnw -f brailleai-api/pom.xml -DskipTests install \
    && ./mvnw -f brailleai-core/pom.xml -DskipTests install \
    && ./mvnw -f brailleai-liblouis/pom.xml -DskipTests install \
    && ./mvnw -f brailleai-vision/pom.xml -DskipTests install \
    && ./mvnw -f brailleai-web/pom.xml -DskipTests install \
    && ./mvnw -f brailleai-application/pom.xml -DskipTests clean package \
    && JAR_PATH="$(find /src/brailleai-application/target -maxdepth 1 -name '*.jar' ! -name 'original-*.jar' | head -n 1)" \
    && test -n "$JAR_PATH" \
    && cp "$JAR_PATH" /tmp/app.jar

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends liblouis-bin curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar
COPY liblouis/tables /app/liblouis/tables

ENV SERVER_PORT=8080
ENV VISION_SERVICE_BASE_URL=http://vision:8000
ENV JAVA_TOOL_OPTIONS="-DLOUIS_CLI_PATH=/usr/bin/lou_translate -DLOUIS_TABLE=/usr/share/liblouis/tables/en-us-g2.ctb"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
