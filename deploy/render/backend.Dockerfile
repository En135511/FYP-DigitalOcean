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

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        default-jre-headless \
        liblouis-bin \
        curl \
        libglib2.0-0 \
        libgomp1 \
        libxcb1 \
        libx11-6 \
        libxext6 \
        libxrender1 \
        libgl1 \
        libsm6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY vision-python-service/requirements.txt /app/vision-python-service/requirements.txt
RUN python -m pip install --no-cache-dir -r /app/vision-python-service/requirements.txt

COPY --from=build /tmp/app.jar /app/app.jar
COPY vision-python-service /app/vision-python-service
COPY liblouis/tables /app/liblouis/tables
COPY deploy/render/start-backend.sh /app/start-backend.sh
RUN chmod +x /app/start-backend.sh

ENV JAVA_TOOL_OPTIONS="-DLOUIS_CLI_PATH=/usr/bin/lou_translate -DLOUIS_TABLE=/usr/share/liblouis/tables/en-us-g2.ctb"
ENV YOLO_MODEL_PATH="/app/vision-python-service/model/yolov8_braille.pt"
ENV VISION_PORT=8000

EXPOSE 10000

ENTRYPOINT ["/app/start-backend.sh"]
