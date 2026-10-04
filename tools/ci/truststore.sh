#!/usr/bin/env bash
# Builds a Java truststore: the CA certificates of the JDK plus the corporate roots, for Gradle, the wrapper and the test JVMs.
#   tools/ci/truststore.sh <jdk home> <directory or file with corporate *.crt / *.pem> <output .jks> [password]
set -euo pipefail
jdk="$1"; certs="$2"; out="$3"; pass="${4:-changeit}"
mkdir -p "$(dirname "$out")"
cp "$jdk/lib/security/cacerts" "$out"
chmod u+w "$out"
if [ -d "$certs" ]; then files=$(find "$certs" -maxdepth 1 -type f \( -name '*.crt' -o -name '*.pem' -o -name '*.cer' \)); else files="$certs"; fi
for f in $files; do
  alias="corp-$(basename "$f" | sed 's/\.[^.]*$//')"
  "$jdk/bin/keytool" -importcert -noprompt -trustcacerts -alias "$alias" -file "$f" -keystore "$out" -storepass "$pass" >/dev/null
  echo "imported $alias"
done
