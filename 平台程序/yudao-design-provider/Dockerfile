# Build in the isolated release environment after Maven has produced the audited jar.
FROM eclipse-temurin:17.0.17_10-jdk-jammy@sha256:73c73439d25b953b34e0065172ad8ce01bea5bc8fc25158f0eeecb4c06d14fed AS health
WORKDIR /health
COPY deploy/platform/Healthcheck.java ./Healthcheck.java
COPY deploy/platform/HmacSmoke.java ./HmacSmoke.java
RUN javac --release 17 Healthcheck.java HmacSmoke.java

FROM eclipse-temurin:17.0.17_10-jre-jammy@sha256:03c5b280ad53c041741552f231f5b65d97be39ca74fa4c7c1c9ace7f42cc3c9e
WORKDIR /opt/design-provider
RUN groupadd --gid 10002 design-provider \
    && useradd --uid 10002 --gid 10002 --no-create-home design-provider
COPY --chown=10002:10002 yudao-design-provider/target/yudao-design-provider.jar ./app.jar
COPY --from=health --chown=10002:10002 /health/Healthcheck.class ./health/Healthcheck.class
COPY --from=health --chown=10002:10002 /health/HmacSmoke.class ./smoke/HmacSmoke.class
USER 10002:10002
EXPOSE 48443
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 CMD ["java", "-cp", "/opt/design-provider/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/design-provider/app.jar"]
