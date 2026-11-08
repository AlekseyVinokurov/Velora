#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$repo_dir"
local_config=false
ci_config=false
low_memory=false
arm64_only=false
for argument in "$@"; do
    case "$argument" in
        --local) local_config=true ;;
        --ci) ci_config=true ;;
        --low-memory) low_memory=true ;;
        --arm64-only) arm64_only=true ;;
        *) echo 'Usage: build-stable.sh [--local | --ci] [--low-memory] [--arm64-only]' >&2; exit 1 ;;
    esac
done
[[ -z $(git status --porcelain=v1 --untracked-files=all) ]] || { echo 'Commit reviewed source changes before a stable build.' >&2; exit 1; }
source_commit=$(git rev-parse HEAD)
if $ci_config; then
    [[ $local_config == false && ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted \
        && ${GITHUB_EVENT_NAME:-} == workflow_dispatch && ${GITHUB_REF:-} == refs/heads/main \
        && ${GITHUB_REPOSITORY:-} == AlekseyVinokurov/Velora ]] || {
        echo 'CI signing requires a manual main run on a GitHub-hosted runner.' >&2; exit 1;
    }
    for name in RELEASE_STORE_FILE RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD TELEGRAM_API_ID TELEGRAM_API_HASH RUNNER_TEMP; do
        [[ -n ${!name:-} ]] || { printf 'Missing CI configuration: %s\n' "$name" >&2; exit 1; }
    done
    [[ ! -e private.properties ]] || { echo 'CI must use ephemeral environment credentials only.' >&2; exit 1; }
elif $local_config; then
    [[ -f private.properties ]] || { echo 'Run python3 scripts/configure-local-release.py interactively first.' >&2; exit 1; }
else
    [[ -t 0 ]] || { echo 'Run interactively, or use --local after local setup.' >&2; exit 1; }
    read -r -p "External keystore path [$HOME/.config/velora/signing/velora-release.p12]: " RELEASE_STORE_FILE
    RELEASE_STORE_FILE=${RELEASE_STORE_FILE:-$HOME/.config/velora/signing/velora-release.p12}
    read -r -p 'Key alias [velora-release]: ' RELEASE_KEY_ALIAS
    RELEASE_KEY_ALIAS=${RELEASE_KEY_ALIAS:-velora-release}
    read -r -s -p 'Keystore password: ' RELEASE_STORE_PASSWORD; printf '\n'
    read -r -s -p 'Key password [Enter = same as keystore]: ' RELEASE_KEY_PASSWORD; printf '\n'
    RELEASE_KEY_PASSWORD=${RELEASE_KEY_PASSWORD:-$RELEASE_STORE_PASSWORD}
    export RELEASE_STORE_FILE RELEASE_KEY_ALIAS RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD
    if [[ ! -f private.properties && -z ${TELEGRAM_API_HASH:-} ]]; then
        read -r -p 'Your Telegram API ID: ' TELEGRAM_API_ID
        read -r -s -p 'Your Telegram API HASH: ' TELEGRAM_API_HASH; printf '\n'
        export TELEGRAM_API_ID TELEGRAM_API_HASH
    fi
fi
# One-shot daemon; configuration cache is disabled so signing parameters are not serialized.
build_options=()
if $ci_config; then build_options+=(--no-build-cache); fi
if $low_memory; then
    build_options+=(--max-workers=1 --no-parallel
        '-Dorg.gradle.jvmargs=-Xmx3072M -XX:MaxMetaspaceSize=768M -XX:+UseG1GC -Dfile.encoding=UTF-8'
        -Pkotlin.compiler.execution.strategy=in-process -PveloraNativeJobs=2)
fi
if $arm64_only; then build_options+=(-PngArm64Only); fi
./gradlew --no-daemon --no-configuration-cache --no-scan --console=plain "${build_options[@]}" :TMessagesProj_AppStandalone:assembleAfatStandalone
unset RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD TELEGRAM_API_HASH
[[ $(git rev-parse HEAD) == "$source_commit" && -z $(git status --porcelain=v1 --untracked-files=all) ]] || {
    echo 'Source changed during the build; do not release this artifact.' >&2; exit 1;
}
receipt=.local-build/stable-build.json
if $ci_config; then receipt="$RUNNER_TEMP/velora-artifacts/stable-build.json"; fi
python3 scripts/release.py verify TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk --receipt "$receipt"
