#!/usr/bin/env bash
# Drift launcher. Copyright (c) 2026 Babar Manzoor. All rights reserved.
# Builds Drift.apk with no Gradle. Needs: java, ecj.jar, android.jar (API 23+), dx.jar,
# apksigner.jar + apksig.jar, python3 with Pillow.
# Point SDKJ at the folder holding those jars.
set -euo pipefail
cd "$(dirname "$0")/.."
SDKJ="${SDKJ:-$HOME/sdk/root/usr/share/java}"
ANDROID_JAR="${ANDROID_JAR:-$SDKJ/com.android.android-23.jar}"
OUT="${OUT:-build}"
rm -rf "$OUT" && mkdir -p "$OUT/classes"

java -cp "$SDKJ/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
  -source 8 -target 8 -nowarn -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" $(find src -name "*.java")

java -jar "$SDKJ/com.android.dx.jar" --dex --min-sdk-version=23 \
  --output="$OUT/classes.dex" "$OUT/classes"

python3 tools/build.py "$OUT/classes.dex" "$OUT/unsigned.apk" "$OUT/icon.png"

KS="${KS:-tools/drift.keystore}"
# The signing key never goes in the repo. Its password comes from the environment: KS_PASS=... bash tools/build.sh
: "${KS_PASS:?Set KS_PASS to the signing key password}"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias drift -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=Babar Manzoor, O=Drift"
fi
export KS_PASS
java -cp "$SDKJ/apksigner.jar:$SDKJ/apksig.jar" com.android.apksigner.ApkSignerTool sign \
  --ks "$KS" --ks-pass env:KS_PASS --ks-key-alias drift --min-sdk-version 23 \
  --out "$OUT/Drift.apk" "$OUT/unsigned.apk"
java -cp "$SDKJ/apksigner.jar:$SDKJ/apksig.jar" com.android.apksigner.ApkSignerTool verify "$OUT/Drift.apk"
echo "Built $OUT/Drift.apk"
