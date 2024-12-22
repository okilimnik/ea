FROM clojure AS lein
WORKDIR /src
COPY . /src
RUN clj -M -e "(compile 'hft.core)"
RUN clj -M:uberjar --main-class hft.core

FROM container-registry.oracle.com/graalvm/jdk:22
RUN microdnf install freetype fontconfig
COPY --from=lein /src/target/hft.jar ./
CMD ["java", "-jar", "hft.jar"]