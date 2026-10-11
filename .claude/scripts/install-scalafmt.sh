#!/usr/bin/env bash
# Installs the native scalafmt binary (no JVM: it checks a file in tens of milliseconds) for the version in
# .scalafmt.conf, at ~/.cache/tda4j/scalafmt-<version>/scalafmt, where .githooks/pre-commit looks for it.
# Linux x86-64 and macOS only; elsewhere use coursier: `cs install scalafmt`. Safe to rerun: it does nothing when the
# binary is already there. Run from the repository root.
set -eu
version=$(sed -n 's/^version *= *"\(.*\)".*/\1/p' .scalafmt.conf)
[ -n "$version" ] || { echo "install-scalafmt: no version in .scalafmt.conf (run from the repository root)" >&2; exit 1; }
dir="$HOME/.cache/tda4j/scalafmt-$version"
bin="$dir/scalafmt"
if [ -x "$bin" ]; then
  echo "install-scalafmt: $bin is already installed"
  exit 0
fi
case "$(uname -s)/$(uname -m)" in
  Linux/x86_64) asset=scalafmt-linux-musl ;;
  Darwin/*) asset=scalafmt-macos ;;
  *)
    echo "install-scalafmt: no native scalafmt for $(uname -s)/$(uname -m); use 'cs install scalafmt'" >&2
    exit 1
    ;;
esac
mkdir -p "$dir"
curl -fsSL --retry 3 -o "$bin.part" "https://github.com/scalameta/scalafmt/releases/download/v$version/$asset"
chmod +x "$bin.part"
"$bin.part" --version > /dev/null # fails here, before installing, if the binary cannot run on this machine
mv "$bin.part" "$bin"
echo "install-scalafmt: installed $("$bin" --version) at $bin"
