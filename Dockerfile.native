FROM clojure AS lein
WORKDIR /app
COPY . /app
RUN clj -M -e "(compile 'ea.core)"
RUN clj -M:uberjar --main-class ea.core

FROM container-registry.oracle.com/graalvm/native-image:22 AS graalvm
WORKDIR /src
COPY --from=lein /app/target/app.jar ./
RUN native-image --report-unsupported-elements-at-runtime --features=clj_easy.graal_build_time.InitClojureClasses --initialize-at-run-time=io.grpc.netty.shaded.io.netty --no-fallback --no-server --enable-url-protocols=https -jar /src/app.jar -H:Name=/src/app

FROM debian:bookworm-slim
COPY --from=graalvm /src/app /src/app
CMD ["/src/app", "-d"]