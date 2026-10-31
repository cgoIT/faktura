# syntax=docker/dockerfile:1

# ---- build: Liberica JDK 25, layered jar --------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM bellsoft/liberica-runtime-container:jdk-25-glibc AS build
WORKDIR /build

# dependencies first, so that they stay cached while only the sources change
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src src
# no .git in the build context: the commit comes in as a build argument (shown in the footer)
ARG GIT_COMMIT=unknown
RUN ./mvnw -B -ntp -DskipTests -Dgit.commit.id.abbrev="$(echo "$GIT_COMMIT" | cut -c1-7)" package \
    && cp target/faktura-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination /build/extracted

# ---- run: Liberica JRE 25 -------------------------------------------------------------------------------------------
FROM bellsoft/liberica-runtime-container:jre-25-glibc

# FAKTURA_CONTAINER makes the application refuse the dev profile (it would reset the database)
ENV FAKTURA_CONTAINER=true \
    SERVER_PORT=8080 \
    SERVER_ADDRESS=0.0.0.0 \
    DB_URL=jdbc:sqlite:/data/faktura.db \
    ARCHIVE_PATH=/data/archive \
    BACKUP_PATH=/data/backup \
    LOGGING_FILE_NAME=/data/logs/faktura.log \
    TZ=Europe/Berlin \
    JAVA_TOOL_OPTIONS="-Duser.timezone=Europe/Berlin -Dfile.encoding=UTF-8 -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"

RUN addgroup -S -g 10001 faktura \
    && adduser -S -u 10001 -G faktura -h /app faktura \
    && mkdir -p /data/archive /data/backup /data/logs \
    && chown -R faktura:faktura /data

WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER faktura:faktura
# database, archive, backups and logs; back this volume up (docs/betrieb.md)
VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O - "http://127.0.0.1:${SERVER_PORT}/actuator/health" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
