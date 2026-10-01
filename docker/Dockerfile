# syntax=docker/dockerfile:1
#
# Two runtimes are produced from this file:
#
#   * jvm-runtime    - Spring Boot executable JAR on a Java 21 runtime
#   * native-runtime - GraalVM native image without a bundled JVM (default target)
#
# The native build compiles with GraalVM for JDK 25 even though the application
# targets Java 21. options.release in build.gradle keeps the bytecode level of
# the JVM artifact independent of the JDK that drives Gradle.

ARG TEMURIN_JDK_IMAGE=eclipse-temurin:21-jdk
ARG JVM_RUNTIME_IMAGE=eclipse-temurin:21-jre-ubi9-minimal
ARG GRAALVM_NATIVE_IMAGE=ghcr.io/graalvm/native-image-community:25-ol9
ARG NATIVE_RUNTIME_IMAGE=registry.access.redhat.com/ubi9/ubi-minimal:latest

FROM ${TEMURIN_JDK_IMAGE} AS jvm-build

WORKDIR /workspace

# Only the build inputs are copied so the Gradle cache mounts stay effective.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src

ARG GIT_COMMIT=unknown

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -PgitCommit="${GIT_COMMIT}"

FROM ${GRAALVM_NATIVE_IMAGE} AS native-build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src

ARG GIT_COMMIT=unknown

# nativeCompile runs the Spring AOT engine and native-image in one step. The
# reflection metadata for the MCP tool DTOs is part of the sources.
# appJavaVersion=25 selects the GraalVM JDK that drives Gradle, because this image
# ships no separate Java 21 JDK. options.release keeps the bytecode level at 21.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon nativeCompile -PappJavaVersion=25 -PgitCommit="${GIT_COMMIT}"

FROM ${JVM_RUNTIME_IMAGE} AS jvm-runtime

ARG IMAGE_VERSION=local
ARG GIT_COMMIT=unknown

LABEL org.opencontainers.image.title="interlis-mcp-jvm" \
      org.opencontainers.image.description="MCP server for INTERLIS 2 (JVM)" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}"

ARG UID=1001
RUN adduser -u ${UID} interlis

WORKDIR /application

COPY --from=jvm-build --chown=${UID}:0 /workspace/build/libs/interlis-mcp.jar ./application.jar

RUN chown ${UID}:0 . && chmod 0755 .

USER ${UID}

# MCP speaks over STDIN and STDOUT: the client must never allocate a TTY.
ENTRYPOINT ["java", "-jar", "/application/application.jar"]

FROM ${NATIVE_RUNTIME_IMAGE} AS native-runtime

ARG IMAGE_VERSION=local
ARG GIT_COMMIT=unknown

LABEL org.opencontainers.image.title="interlis-mcp" \
      org.opencontainers.image.description="MCP server for INTERLIS 2 (GraalVM native image)" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}"

# A native image needs no JVM; tzdata and ca-certificates are its only runtime
# dependencies beyond glibc.
RUN microdnf install -y tzdata ca-certificates \
    && microdnf clean all \
    && mkdir -p /application \
    && chown -R 1001:0 /application

WORKDIR /application

COPY --from=native-build --chown=1001:0 \
     /workspace/build/native/nativeCompile/interlis-mcp ./interlis-mcp

# OpenShift may assign an arbitrary UID with group 0.
RUN chgrp -R 0 /application \
    && chmod -R g=u /application

USER 1001:0

# MCP speaks over STDIN and STDOUT: the client must never allocate a TTY.
ENTRYPOINT ["/application/interlis-mcp"]
