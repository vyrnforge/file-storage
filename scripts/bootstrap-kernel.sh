#!/usr/bin/env bash
# Public release artifact, not a GitHub Packages token or vendored source.
set -euo pipefail
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
jar="$dir/platform-kernel-0.3.0.jar"
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 --max-time 120 \
  https://github.com/vyrnforge/platform-kernel/releases/download/v0.3.0/platform-kernel-0.3.0.jar -o "$jar"
printf '%s  %s\n' '8fce5cafbbab7fa93cbcc876cb6039e04e2dcdc453fbf9a70db7734517189ae3' "$jar" | sha256sum --check
mvn -B -ntp org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile="$jar" -DgroupId=io.vyrnforge.platform -DartifactId=platform-kernel \
  -Dversion=0.3.0 -Dpackaging=jar -DgeneratePom=true
