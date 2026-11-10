#!/usr/bin/env bash
# Android compilation is intentionally restricted to GitHub-hosted runners.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
root=$(git rev-parse --show-toplevel)
source_file="$root/TMessagesProj/src/main/java/soundgram/wsproxy/tg-ws-proxy.go"
ndk="$ANDROID_HOME/ndk/26.3.11579264/toolchains/llvm/prebuilt/linux-x86_64/bin"
go version
# This engine uses only the Go standard library. No modules or third-party downloads.
export CGO_ENABLED=1 GOOS=android GO111MODULE=off
for abi in arm64-v8a armeabi-v7a; do
  if [[ "$abi" == arm64-v8a ]]; then
    export GOARCH=arm64 CC="$ndk/aarch64-linux-android24-clang"
    unset GOARM
  else
    export GOARCH=arm GOARM=7 CC="$ndk/armv7a-linux-androideabi24-clang"
  fi
  output_dir="$root/.local-build/native-libs/$abi"
  mkdir -p "$output_dir"
  cp "$root/TMessagesProj/src/main/jniLibs/$abi/libpine.so" "$output_dir/"
  go build -trimpath -buildmode=c-shared -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' \
    -o "$output_dir/libtgwsproxy.so" "$source_file"
done
