#!/usr/bin/env bash
set -euo pipefail

# 错题小印 BY-288 reproducible build helper for the portable Android environment.
# Usage:
#   tools/build-by288-alpha1.sh /path/to/android_offline_env_complete /path/to/LaBLEr-1.1.0.apk [output.apk]

ENV_ROOT=${1:?environment root required}
UPSTREAM_APK=${2:?upstream LaBLEr 1.1.0 APK required}
OUT=${3:-"$PWD/cuotixiaoyin.apk"}
PROJECT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

"$ENV_ROOT/scripts/build-offline.sh" "$PROJECT" :app:assembleDebug
BASE_APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"

DEXLIB="$ENV_ROOT/android-sdk/cmdline-tools/latest/lib/external/com/android/tools/smali/smali-dexlib2/3.0.3/smali-dexlib2-3.0.3.jar"
UTIL="$ENV_ROOT/android-sdk/cmdline-tools/latest/lib/external/com/android/tools/smali/smali-util/3.0.3/smali-util-3.0.3.jar"
GUAVA="$ENV_ROOT/maven-repo/com/google/guava/guava/32.0.1-jre/guava-32.0.1-jre.jar"
FAIL="$ENV_ROOT/maven-repo/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar"
CP="$DEXLIB:$UTIL:$GUAVA:$FAIL"

unzip -p "$UPSTREAM_APK" classes.dex > "$TMP/upstream.dex"
"$ENV_ROOT/jdk17/bin/javac" -d "$TMP" -cp "$CP" "$PROJECT/tools/ExtractPackageDex.java"
"$ENV_ROOT/jdk17/bin/java" -cp "$TMP:$CP" ExtractPackageDex "$TMP/upstream.dex" 'Lcom/google/zxing/' "$TMP/zxing.dex"

cp "$BASE_APK" "$TMP/base.apk"
max=1
while IFS= read -r n; do
  b=$(basename "$n")
  if [[ "$b" == classes.dex ]]; then i=1; else i=${b#classes}; i=${i%.dex}; fi
  (( i > max )) && max=$i
done < <(unzip -Z1 "$TMP/base.apk" | grep -E '^classes([0-9]+)?\.dex$')
next=$((max+1))
cp "$TMP/zxing.dex" "$TMP/classes${next}.dex"
(cd "$TMP" && zip -q -0 base.apk "classes${next}.dex")

BT="$ENV_ROOT/android-sdk/build-tools/36.1.0"
"$BT/zipalign" -f -P 16 4 "$TMP/base.apk" "$TMP/aligned.apk"
KS=${BY288_SIGNING_KEYSTORE:-"$PROJECT/signing/by288-test.jks"}
"$BT/apksigner" sign --ks "$KS" --ks-key-alias by288test --ks-pass pass:android --key-pass pass:android --out "$TMP/signed.apk" "$TMP/aligned.apk"
"$BT/apksigner" verify --verbose "$TMP/signed.apk"
"$BT/zipalign" -c -P 16 4 "$TMP/signed.apk"
cp "$TMP/signed.apk" "$OUT"
echo "Built: $OUT"
