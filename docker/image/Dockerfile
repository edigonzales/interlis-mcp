FROM eclipse-temurin:21.0.8_9-jre-ubi9-minimal@sha256:73d448928ec317a42672de5611d6291753c0e010e1f183fa3fc0ce8f00af4317

ARG UID=1001
RUN adduser -u $UID interlis

ENV HOME=/application
WORKDIR /application

COPY tmp/interlis-mcp.jar ./application.jar

RUN chown $UID:0 . && chmod 0755 .

USER $UID

ENTRYPOINT ["java", "-jar", "/application/application.jar"]
