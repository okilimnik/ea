FROM clojure AS lein
WORKDIR /src
COPY . /src
RUN clj -M -e "(compile 'ea.core)"
RUN clj -M:uberjar --main-class ea.core

FROM container-registry.oracle.com/graalvm/jdk:22
RUN microdnf install freetype fontconfig
COPY --from=lein /src/target/ea.jar ./
CMD ["java", "-jar", "ea.jar"]