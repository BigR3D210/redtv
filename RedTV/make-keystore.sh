#!/usr/bin/env bash
# Generates a release signing keystore for Red TV.
# Run once. Requires the JDK 'keytool' (bundled with Android Studio / any JDK).
set -e

ALIAS="redtv"
STORE="redtv-release.jks"

if [ -f "$STORE" ]; then
  echo "$STORE already exists. Delete it first if you really want a new one."
  exit 1
fi

read -rsp "Choose a keystore password: " PASS; echo

keytool -genkeypair -v \
  -keystore "$STORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Red TV, OU=Personal, O=Red, L=City, S=State, C=US"

cat > keystore.properties <<EOF
storeFile=$STORE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF

echo
echo "Done. Created $STORE and keystore.properties."
echo "Now build a signed APK:  ./gradlew assembleRelease"
echo "Output: app/build/outputs/apk/release/app-release.apk"
echo "IMPORTANT: back up $STORE - you need the SAME keystore to update the app later."
