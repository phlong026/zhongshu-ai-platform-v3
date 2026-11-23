# Build only in the isolated build environment; context is 平台程序/.
FROM eclipse-temurin:17.0.17_10-jdk-jammy@sha256:73c73439d25b953b34e0065172ad8ce01bea5bc8fc25158f0eeecb4c06d14fed AS health
WORKDIR /health
COPY deploy/platform/Healthcheck.java ./Healthcheck.java
COPY deploy/platform/CapabilitySmoke.java ./CapabilitySmoke.java
RUN javac --release 17 Healthcheck.java CapabilitySmoke.java

FROM eclipse-temurin:17.0.17_10-jre-jammy@sha256:03c5b280ad53c041741552f231f5b65d97be39ca74fa4c7c1c9ace7f42cc3c9e
WORKDIR /opt/platform
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg libheif-examples \
    && rm -rf /var/lib/apt/lists/* \
    && ffmpeg -version && ffprobe -version && heif-convert --help \
    && groupadd --gid 10001 platform && useradd --uid 10001 --gid 10001 --no-create-home platform \
    && install -d -m 0700 -o 10001 -g 10001 /var/lib/platform/asset-spool /var/lib/platform/training-private
COPY --chown=10001:10001 yudao-platform-server/target/yudao-platform-server.jar ./app.jar
COPY --from=health --chown=10001:10001 /health/Healthcheck.class ./health/Healthcheck.class
COPY --from=health --chown=10001:10001 /health/CapabilitySmoke.class ./smoke/CapabilitySmoke.class
USER 10001:10001
EXPOSE 48080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 CMD ["java", "-cp", "/opt/platform/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/platform/app.jar"]
