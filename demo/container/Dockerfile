# The demo REPL for a participant who has Docker and no Java: a terminal in a web page, with the plots on a second
# port.
#
#   docker run --rm -p 127.0.0.1:7681:7681 -p 127.0.0.1:8337:8337 ghcr.io/appliedtopology/tda4j-demo
#
# then http://localhost:7681 is the prompt and http://localhost:8337 shows what `view()` draws. Built from the demo's
# folder, after `sbt assembly` (README.md there):
#
#   docker build -f container/Dockerfile --build-arg JAR=target/out/jvm/scala-3.9.0/tda4j-demo/tda4j-demo-<version>.jar \
#     -t tda4j-demo .
ARG BASE=eclipse-temurin:21-jre
FROM ${BASE}

# ttyd serves a terminal as a web page; dtach keeps the REPL running while no page is attached to it; tini is the
# container's first process, which collects the REPLs that have ended.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ttyd dtach tini \
 && rm -rf /var/lib/apt/lists/*

# Any user may run the session (`docker run --user "$(id -u):$(id -g)"`, so that files written into a mounted folder
# are the participant's own): the home and working folders are writable by everyone.
RUN mkdir -p /opt/tda4j /home/tda4j /work && chmod 1777 /home/tda4j /work
ARG JAR
COPY ${JAR} /opt/tda4j/tda4j-demo.jar
COPY --chmod=755 container/start.sh /opt/tda4j/start.sh

# The live viewer inside a container must listen on the address Docker publishes, not on the loopback one, and has no
# desktop to open a browser on. C.UTF-8: the REPL and the library print non-ASCII characters.
ENV HOME=/home/tda4j \
    LANG=C.UTF-8 \
    TDA4J_PLOT_HOST=0.0.0.0 \
    TDA4J_PLOT_BROWSER=none
USER 1000:1000
WORKDIR /work
EXPOSE 7681 8337
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/tda4j/start.sh"]
