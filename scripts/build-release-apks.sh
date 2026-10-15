#!/usr/bin/env bash
# Build and verify separate, unsigned release distributions. Never installs or publishes.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
case "${1:-all}" in
    all) abis=(arm64-v8a x86_64) ;;
    arm64-v8a|x86_64) abis=("$1") ;;
    *) printf 'Usage: %s [all|arm64-v8a|x86_64]\n' "$0" >&2; exit 2 ;;
esac
for abi in "${abis[@]}"; do
    ./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease "-Phelix.abi=$abi"
    destination="$repo_root/build/distributions/$abi"
    for flavor in consumer developer; do
        mkdir -p "$destination/apk/$flavor/release" "$destination/mapping/${flavor}Release"
        cp "app/build/outputs/apk/$flavor/release/app-$flavor-release-unsigned.apk" \
            "$destination/apk/$flavor/release/"
        cp "app/build/outputs/apk/$flavor/release/output-metadata.json" \
            "$destination/apk/$flavor/release/"
        cp app/build/outputs/mapping/"${flavor}Release"/*.txt "$destination/mapping/${flavor}Release/"
    done
    python3 scripts/verify-integrated-runtime-apks.py --build-type release \
        --artifact-root "$destination" --expected-abi "$abi"
    shasum -a 256 "$destination"/apk/*/release/*.apk > "$destination/apks.sha256"
    printf 'Verified unsigned %s distributions: %s\n' "$abi" "$destination"
done
