# Build only in the isolated build environment; context is 平台程序/.
FROM eclipse-temurin:17.0.17_10-jdk-jammy AS health
WORKDIR /health
COPY deploy/platform/Healthcheck.java ./Healthcheck.java
RUN javac --release 17 Healthcheck.java

FROM eclipse-temurin:17.0.17_10-jre-jammy
WORKDIR /opt/platform
RUN groupadd --gid 10001 platform && useradd --uid 10001 --gid 10001 --no-create-home platform
COPY --chown=10001:10001 yudao-platform-server/target/yudao-platform-server.jar ./app.jar
COPY --from=health --chown=10001:10001 /health/Healthcheck.class ./health/Healthcheck.class
USER 10001:10001
EXPOSE 48080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 CMD ["java", "-cp", "/opt/platform/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/platform/app.jar"]
