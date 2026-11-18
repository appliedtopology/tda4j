#!/bin/sh
# What the container runs.
#
#   (no argument)   the REPL behind a terminal in a web page, on port 7681
#   repl [...]      the REPL in this terminal (`docker run -it ... repl`); arguments go to the jar
#   anything else   is run as a command (`docker run -it ... bash`)
#
# JAVA_OPTS reaches the JVM either way (`-e JAVA_OPTS=-Xmx6g`).
set -e
jar=/opt/tda4j/tda4j-demo.jar

case "${1:-web}" in
  web)
    echo "TDA4j demo: the prompt is at http://localhost:7681, and plots appear at http://localhost:8337 after the first view()."
    echo "(Those are the addresses when the container was started with -p 127.0.0.1:7681:7681 -p 127.0.0.1:8337:8337.)"
    # One REPL for the container, which every page attaches to (dtach -A: attach, or start it first). A page that
    # reloads, or reconnects after the laptop slept, is back in the same session with its values; `:quit` ends the
    # session and the page starts a new one. -E, -z: no detach or suspend keys, every key goes to the REPL.
    # -r winch: on attaching, the REPL is told the window changed, and redraws its prompt.
    # shellcheck disable=SC2086
    exec ttyd --writable --port 7681 \
      -t titleFixed="TDA4j" -t disableLeaveAlert=true -t fontSize=15 \
      dtach -A /tmp/tda4j-session -E -z -r winch java $JAVA_OPTS -jar "$jar"
    ;;
  repl)
    shift
    # shellcheck disable=SC2086
    exec java $JAVA_OPTS -jar "$jar" "$@"
    ;;
  *)
    exec "$@"
    ;;
esac
