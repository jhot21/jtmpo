#!/usr/bin/env bash
# Derives the Android version from a release tag. Used by .github/workflows/release.yml.
#
#   release-version.sh vX.Y.Z[-rc.N|-beta.N]
#
# Prints name=, code= and prerelease= lines (ready to append to $GITHUB_OUTPUT). On a tag that
# is not one of the accepted forms it prints one ::error:: annotation and exits 1.
#
# versionCode = ((major*100 + minor)*100 + patch)*100 + stage, with stage 99 for a final,
# 50+N for rc.N (N 1..48) and 10+N for beta.N (N 1..39). So beta < rc < final for one version,
# and every stage of a later version outranks any stage of an earlier one.
set -euo pipefail

tag=${1:-}
re='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(rc|beta)\.([1-9][0-9]*))?$'

fail() {
  echo "::error::$1"
  exit 1
}

[[ "$tag" =~ $re ]] || fail "Tag '$tag' must be vX.Y.Z, vX.Y.Z-rc.N or vX.Y.Z-beta.N"

major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}
kind=${BASH_REMATCH[5]} n=${BASH_REMATCH[6]}

# Keep every number short enough that the arithmetic below cannot overflow.
for part in "$major" "$minor" "$patch" "$n"; do
  (( ${#part} <= 9 )) || fail "Tag '$tag' has a number that is too large"
done
(( minor <= 99 && patch <= 99 )) || fail "minor and patch must each be at most 99"

name="$major.$minor.$patch"
case "$kind" in
  rc)
    (( n <= 48 )) || fail "rc number must be 1 to 48"
    stage=$(( 50 + n )) prerelease=true
    name+="-rc.$n"
    ;;
  beta)
    (( n <= 39 )) || fail "beta number must be 1 to 39"
    stage=$(( 10 + n )) prerelease=true
    name+="-beta.$n"
    ;;
  *)
    stage=99 prerelease=false
    ;;
esac

code=$(( ((major * 100 + minor) * 100 + patch) * 100 + stage ))
(( code <= 2100000000 )) || fail "versionCode $code exceeds Android's 2100000000 limit"

echo "name=$name"
echo "code=$code"
echo "prerelease=$prerelease"
