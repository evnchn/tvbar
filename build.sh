#!/bin/bash
# Gradle-free build: javac -> d8 -> aapt2 link -> zipalign -> apksigner (debug key).
# TVBAR_SERVER is the data server the bundled pages fetch from, e.g. TVBAR_SERVER=http://192.0.2.10:8093 ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" PATH="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}" BT="${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/34.0.0"
JAR="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/android-34/android.jar"
rm -rf out && mkdir -p out/classes out/dex
javac --release 8 -nowarn -cp "$JAR" -d out/classes src/com/evnchn/tvbar/*.java
"$BT/d8" --min-api 22 --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
"$BT/aapt2" compile --dir res -o out/res.zip
rm -rf out/assets && mkdir -p out/assets && for f in index.html home.html; do sed "s|__TVBAR_SERVER__|${TVBAR_SERVER:-http://127.0.0.1:8093}|" web/$f > out/assets/$f; done
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml -A out/assets out/res.zip
(cd out/dex && zip -q ../base.apk classes.dex)
"$BT/zipalign" -f 4 out/base.apk out/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias tvbar -keyalg RSA -keysize 2048 -validity 10000 -dname CN=tvbar >/dev/null
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out tvbar.apk out/aligned.apk
echo built "$PWD/tvbar.apk"
