#!/usr/bin/env bash
# Pipeline build APK AVI — tanpa Gradle, tanpa dependensi eksternal.
# aapt2 -> ECJ -> d8 -> zip -> zipalign -> apksigner
set -e

SDK=/home/z/my-project/.tooling/android-sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-34/android.jar
PJ=/home/z/my-project/AVI/android
SRC=$PJ/src
OUT=$PJ/build
KST=/home/z/my-project/AVI/avi.keystore

VER=1.4
APK_NAME=AVI-v$VER.apk

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

echo "[1/7] aapt2 compile resource..."
"$BT/aapt2" compile --dir "$PJ/res" -o "$OUT/res.zip"

echo "[2/7] aapt2 link (R.java + resource apk)..."
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$PJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 5 --version-name "$VER" \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "[3/7] ECJ compile java..."
# -source/-target 8: android.jar memuat stub java.* yang bentrok dengan
# modul java.base JRE 21 bila memakai source level 9+. Lambda tetap jalan (Java 8).
java -jar "$SDK/ecj.jar" \
  -source 8 -target 8 -encoding UTF-8 -nowarn \
  -cp "$PLAT" \
  -d "$OUT/obj" \
  "$SRC"/com/avi/assistant/*.java \
  "$OUT"/gen/com/avi/assistant/R.java

echo "[4/7] d8 dex..."
CLASSES=$(find "$OUT/obj" -name '*.class' | tr '\n' ' ')
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --lib "$PLAT" --min-api 24 \
  --output "$OUT/dex" $CLASSES

echo "[5/7] sisipkan classes.dex ke apk..."
cd "$OUT/dex"
zip -q -u "$OUT/base.apk" classes.dex

echo "[6/7] zipalign..."
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[7/7] apksigner sign..."
if [ ! -f "$KST" ]; then
  keytool -genkeypair -keystore "$KST" -alias avi \
    -storepass avi12345 -keypass avi12345 \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=AVI Assistant" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KST" --ks-key-alias avi \
  --ks-pass pass:avi12345 --key-pass pass:avi12345 \
  --out "/home/z/my-project/AVI/$APK_NAME" "$OUT/aligned.apk"

"$BT/apksigner" verify "/home/z/my-project/AVI/$APK_NAME"
echo "SELESAI: /home/z/my-project/AVI/$APK_NAME"
ls -la "/home/z/my-project/AVI/$APK_NAME"
