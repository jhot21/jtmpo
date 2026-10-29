#!/usr/bin/env bash
# Tests for release-version.sh. Prints ok/FAIL per case and exits non-zero on any failure.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/release-version.sh"
fails=0

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; fails=$((fails + 1)); }

# expect_ok <tag> <name> <code> <prerelease>
expect_ok() {
  local out want
  want=$(printf 'name=%s\ncode=%s\nprerelease=%s' "$2" "$3" "$4")
  if out=$(bash "$script" "$1" 2>&1) && [[ "$out" == "$want" ]]; then
    pass "$1 -> $2 $3 $4"
  else
    fail "$1 expected [$want] got [$out]"
  fi
}

# expect_reject <tag>
expect_reject() {
  local out rc=0
  out=$(bash "$script" "$1" 2>&1) || rc=$?
  if [[ $rc -eq 1 && "$out" == ::error::* && "$(wc -l <<<"$out")" -eq 1 ]]; then
    pass "reject $1"
  else
    fail "reject $1 (rc=$rc out=[$out])"
  fi
}

code_of() { bash "$script" "$1" | sed -n 's/^code=//p'; }

# expect_less <lower tag> <higher tag>
expect_less() {
  local a b
  if ! a=$(code_of "$1") || ! b=$(code_of "$2"); then
    fail "order $1 < $2 (script failed)"
    return
  fi
  if (( a < b )); then pass "order $1 ($a) < $2 ($b)"; else fail "order $1 ($a) < $2 ($b)"; fi
}

expect_ok v0.3.0 0.3.0 30099 false
expect_ok v0.3.1-rc.2 0.3.1-rc.2 30152 true
expect_ok v0.3.1-beta.1 0.3.1-beta.1 30111 true
expect_ok v1.0.0 1.0.0 1000099 false
expect_ok v1.2.3-rc.48 1.2.3-rc.48 1020398 true
expect_ok v1.2.3-beta.39 1.2.3-beta.39 1020349 true

expect_less v0.3.1-beta.1 v0.3.1-rc.1
expect_less v0.3.1-rc.1 v0.3.1
expect_less v0.3.0 v0.3.1-rc.1
expect_less v0.3.1 v0.3.2-beta.1
if (( $(code_of v0.3.1-rc.1) > 300 )); then pass "v0.3.1-rc.1 above old-scheme v0.3.0 (300)"; else fail "above old scheme"; fi

for t in v1.2 1.2.3 v1.2.3-rc v1.2.3-rc.0 v1.2.3-rc.49 v1.2.3-beta.0 v1.2.3-beta.40 \
  v1.2.3-RC.1 v1.2.3-rc.1-x v1.2.3+meta v01.2.3 v1.100.0 v1.0.100 v2100.0.0 \
  v99999999999999999999.0.0 ""; do
  expect_reject "$t"
done

if (( fails > 0 )); then echo "$fails failed"; exit 1; fi
echo "all passed"
