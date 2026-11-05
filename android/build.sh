#!/usr/bin/env bash
# Сборка «ЯМ Моста» без Android Studio и Gradle.
#
# Нужно: JDK 17+ и Android SDK (build-tools 33+ и platform android-33).
#   ANDROID_HOME   — папка Android SDK (по умолчанию ~/Android/Sdk)
#   KEYSTORE       — ключ подписи (по умолчанию release.jks, создаётся автоматически)
#   KS_PASS / KS_ALIAS — пароль и имя ключа
#   VARIANTS       — какие варианты собирать (по умолчанию все четыре)
#
# Без SDK можно указать инструменты по отдельности: ANDROID_JAR, AAPT2, DX_JAR (dx.jar вместо d8),
# SIGNER_JAR (uber-apk-signer.jar вместо apksigner).
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)
ANDROID_JAR=${ANDROID_JAR:-$(ls "$ANDROID_HOME"/platforms/android-3*/android.jar 2>/dev/null | sort -V | tail -1 || true)}
AAPT2=${AAPT2:-$BT/aapt2}
D8=${D8:-$BT/d8}
APKSIGNER=${APKSIGNER:-$BT/apksigner}
ZIPALIGN=${ZIPALIGN:-$BT/zipalign}
KEYSTORE=${KEYSTORE:-release.jks}; KS_PASS=${KS_PASS:-changeit}; KS_ALIAS=${KS_ALIAS:-ymbridge}
VERSION_CODE=${VERSION_CODE:-12}; VERSION_NAME=${VERSION_NAME:-2.2}
# «разрешённые» имена пакетов, под которыми OriginOS показывает плеер
VARIANTS=${VARIANTS:-"apple:com.apple.android.music hiby:com.hiby.music kuwo:cn.kuwo.player kugou:com.kugou.android"}

[ -f "$ANDROID_JAR" ] || { echo "Не найден android.jar — задайте ANDROID_HOME или ANDROID_JAR"; exit 1; }
[ -x "$AAPT2" ] || { echo "Не найден aapt2 — задайте ANDROID_HOME или AAPT2"; exit 1; }

rm -rf build out && mkdir -p build/classes build/gen out
"$AAPT2" compile --dir res -o build/res.zip
first=1
for v in $VARIANTS; do
  name=${v%%:*}; pkg=${v#*:}
  mkdir -p "build/$name"
  sed "s/@PKG@/$pkg/" AndroidManifest.xml.in > "build/$name/AndroidManifest.xml"
  extra=""; [ $first = 1 ] && extra="--java build/gen --custom-package ymbridge"
  "$AAPT2" link -o "build/$name/base.apk" -I "$ANDROID_JAR" --manifest "build/$name/AndroidManifest.xml" build/res.zip \
     --min-sdk-version 26 --target-sdk-version 33 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" $extra
  first=0
done

javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -d build/classes \
  src/ymbridge/*.java build/gen/ymbridge/R.java
if [ -n "${DX_JAR:-}" ]; then
  java -cp "$DX_JAR" com.android.dx.command.Main --dex --min-sdk-version=26 --output=build/classes.dex build/classes
else
  "$D8" --min-api 26 --output build $(find build/classes -name '*.class')
fi

[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
  -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=YM Bridge"

for v in $VARIANTS; do
  name=${v%%:*}
  cp build/classes.dex "build/$name/" && (cd "build/$name" && zip -q base.apk classes.dex)
  out="out/YM-Bridge-$VERSION_NAME-$name.apk"
  if [ -n "${SIGNER_JAR:-}" ]; then
    cp "build/$name/base.apk" "$out"
  else
    "$ZIPALIGN" -f 4 "build/$name/base.apk" "build/$name/aligned.apk"
    "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" --out "$out" "build/$name/aligned.apk"
  fi
done
if [ -n "${SIGNER_JAR:-}" ]; then
  java -jar "$SIGNER_JAR" -a out --overwrite --allowResign --ks "$KEYSTORE" --ksAlias "$KS_ALIAS" \
    --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS" > build/sign.log 2>&1 || { cat build/sign.log; exit 1; }
fi
rm -f out/*.idsig
echo; echo "Готово:"; ls -1 out
