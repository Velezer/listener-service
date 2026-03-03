#!/usr/bin/env bash
set -euo pipefail

wrapper_props="android/gradle/wrapper/gradle-wrapper.properties"

if [[ ! -f "$wrapper_props" ]]; then
  echo "FAIL: Missing $wrapper_props"
  exit 1
fi

distribution_url="$(sed -n -E 's/^distributionUrl=(.*)$/\1/p' "$wrapper_props")"
if [[ -z "$distribution_url" ]]; then
  echo "FAIL: Could not read distributionUrl from $wrapper_props"
  exit 1
fi

version="$(printf '%s\n' "$distribution_url" | sed -n -E 's#.*gradle-([0-9]+(\.[0-9]+){0,2})-bin\.zip#\1#p')"
if [[ -z "$version" ]]; then
  echo "FAIL: Could not parse Gradle version from distributionUrl: $distribution_url"
  exit 1
fi

major="${version%%.*}"
if [[ "$major" -ge 9 ]]; then
  echo "FAIL: Gradle wrapper is pinned to $version, but this project currently requires Gradle 8.x to avoid AGP dependency-mutation failures during :app:processDebugResources."
  exit 1
fi

echo "OK: Gradle wrapper version $version is within the supported 8.x range for current Android plugin compatibility."
