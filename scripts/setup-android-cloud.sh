#!/usr/bin/env bash
# 클라우드 세션(Ubuntu, JDK 21만 있음)에서 Android 앱을 빌드/테스트할 수 있게 JDK 17 + Android SDK를 설치한다.
# 필요한 네트워크 허용: dl.google.com (SDK/Google Maven), maven.google.com, repo.maven.apache.org, services.gradle.org, plugins.gradle.org
# 사용: source scripts/setup-android-cloud.sh   (환경 변수를 현재 셸에 내보내려면 source로)
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_ZIP="commandlinetools-linux-11076708_latest.zip"

if [ ! -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
  apt-get update -q && apt-get install -y -q openjdk-17-jdk-headless
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK_ROOT/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsS -o "$tmp/cmdline.zip" "https://dl.google.com/android/repository/$CMDLINE_ZIP"
  unzip -q "$tmp/cmdline.zip" -d "$SDK_ROOT/cmdline-tools"
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
# app/build.gradle.kts의 compileSdk(37)에 맞춘다. AGP가 build-tools를 더 요구하면 자동으로 받는다.
"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" "build-tools;36.1.0" "platform-tools"

export ANDROID_HOME="$SDK_ROOT"
echo "sdk.dir=$SDK_ROOT" > "$(dirname "$0")/../local.properties"   # local.properties는 .gitignore 대상
export LC_ALL=C.UTF-8   # 한글 테스트 이름
echo "ready: JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME"
