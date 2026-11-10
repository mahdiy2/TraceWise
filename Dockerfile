FROM gradle:8.13-jdk21 AS build
USER root
RUN mkdir -p /workspace && chown gradle:gradle /workspace
USER gradle
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle :webApp:wasmJsBrowserDistribution :server:installDist --no-daemon

FROM eclipse-temurin:21-jre
RUN groupadd --gid 10001 studio && useradd --uid 10001 --gid studio --create-home studio && mkdir /data && chown studio:studio /data
WORKDIR /app
COPY --from=build /workspace/server/build/install/server/ ./
COPY --from=build /workspace/webApp/build/dist/wasmJs/productionExecutable/ /app/web/
USER studio
ENV STACKTRACE_DATA_DIR=/data
ENV STACKTRACE_WEB_DIR=/app/web
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
