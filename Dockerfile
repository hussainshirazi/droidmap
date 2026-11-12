# droidmap in a container, for CI systems other than GitHub Actions and for MCP clients that run servers with Docker.
# Build the jar first (./gradlew fatJar). The project goes in /project:
#
#   docker run --rm -v "$PWD":/project ghcr.io/hussainshirazi/droidmap check
#   docker run --rm -i -v "$PWD":/project ghcr.io/hussainshirazi/droidmap mcp
FROM eclipse-temurin:21-jre

# git for `diff` and `check --base`; the mounted project belongs to another user, so git is told to trust it.
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && git config --system --add safe.directory '*'

COPY build/libs/droidmap.jar /opt/droidmap/droidmap.jar

LABEL org.opencontainers.image.source="https://github.com/hussainshirazi/droidmap" \
      org.opencontainers.image.description="Android-aware project graph, checks and MCP server" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.modelcontextprotocol.server.name="io.github.hussainshirazi/droidmap"

WORKDIR /project
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-jar", "/opt/droidmap/droidmap.jar"]
CMD ["--help"]
