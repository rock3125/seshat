# The MCP server and chat gateway: one fat jar, one process.
#
# Built with the Gradle wrapper on a plain JDK image rather than the `gradle`
# image, so the build needs exactly one base image and it is the same family as
# the runtime stage.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# The wrapper and build files first, on their own layer: dependency resolution
# is keyed only on these, so editing a .kt file rebuilds without re-downloading
# Gradle or the dependency set.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies -q > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon fatJar -x test

FROM eclipse-temurin:25-jdk
# curl for the compose healthcheck against /health.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/libs/gateway-all.jar ./gateway.jar

# The library folder is mounted here; the default is a real path so a container
# run without the mount logs "does not exist" rather than indexing the root.
ENV LIBRARY_DIR=/library
EXPOSE 8090

# Container-aware heap sizing: the JVM reads the cgroup limit, so a memory cap
# set in compose is actually honoured instead of the JVM sizing against the
# host's total RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/gateway.jar"]
