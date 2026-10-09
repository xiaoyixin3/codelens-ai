# Pinned official JDK base. This separate prototype image is never the API/Worker image.
FROM eclipse-temurin:17-jdk-alpine@sha256:83bb7084d2cdfa954fc34616f91cf971012e2a50d6cfd1edacad06f2e1fff496
LABEL ai.codelens.sandbox-policy="stdlib-javac17-probe-v1"
WORKDIR /opt/codelens-sandbox
COPY src/main/java/ai/codelens/sandbox/JavaSandboxHarness.java ./JavaSandboxHarness.java
RUN javac --release 17 -proc:none -d classes JavaSandboxHarness.java \
 && jar --create --file harness.jar -C classes . \
 && touch container-only \
 && rm -r classes JavaSandboxHarness.java \
 && chmod 555 /opt/codelens-sandbox \
 && chmod 444 harness.jar container-only
USER 10001:10001
ENTRYPOINT ["java", "-Xmx128m", "-XX:+DisableAttachMechanism", "-XX:-UsePerfData", "-Djava.io.tmpdir=/tmp", "-cp", "/opt/codelens-sandbox/harness.jar", "ai.codelens.sandbox.JavaSandboxHarness"]
