#!/bin/sh
# Self-signed TLS certificate for the API hostname (requires openssl).
# The client's certificate callback (HttpsHelper.MyRemoteCertificateValidationCallback) accepts
# an untrusted chain, so this does not need to be installed on the phone in the normal case.
set -e
HOST=${1:-skgm-prod-game-api.skullgirlsmobile.com}
OUT=${2:-.}
openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
  -keyout "$OUT/key.pem" -out "$OUT/cert.pem" \
  -subj "/CN=$HOST" -addext "subjectAltName=DNS:$HOST"
echo "wrote $OUT/cert.pem and $OUT/key.pem for $HOST"
