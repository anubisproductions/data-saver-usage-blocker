#!/usr/bin/env bash
# The app's whole proposition is that it holds no INTERNET permission, and the store listing
# invites people to check. A transitive dependency could add one in a version bump without
# anybody noticing until a user did. This makes the build the thing that notices.
#
#   ./tools/check_permissions.sh              # checks playDebug
#   ./tools/check_permissions.sh fossRelease  # or any other variant
#
# The variant matters: the Play flavour links Play's review library and the FOSS
# flavour does not. They are different merge inputs; checking one proves nothing
# about the other.
#
# Exits non-zero if a forbidden permission appears, or if the expected set drifts.
set -euo pipefail

VARIANT="${1:-playDebug}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

FORBIDDEN=(
  "android.permission.INTERNET"
  "android.permission.QUERY_ALL_PACKAGES"
  "android.permission.ACCESS_FINE_LOCATION"
  "android.permission.READ_CONTACTS"
)

EXPECTED=(
  "android.permission.ACCESS_NETWORK_STATE"
  "android.permission.FOREGROUND_SERVICE"
  "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
  "android.permission.PACKAGE_USAGE_STATS"
  "android.permission.POST_NOTIFICATIONS"
  "android.permission.RECEIVE_BOOT_COMPLETED"
)

# Match the variant directory exactly - a bare "*debug*" glob also matches fossDebug
# and playDebug, so an unqualified name would check whichever find listed first.
MANIFEST=$(find "$ROOT/app/build/intermediates/merged_manifest/$VARIANT" \
             -name AndroidManifest.xml 2>/dev/null | head -1)

if [ -z "$MANIFEST" ]; then
  echo "No merged $VARIANT manifest found. Build first:  ./gradlew assemble${VARIANT^}"
  exit 2
fi

FOUND=$(grep -o 'android:name="android\.permission\.[A-Z_]*"' "$MANIFEST" \
        | sed 's/.*"\(.*\)"/\1/' | sort -u)

fail=0

for p in "${FORBIDDEN[@]}"; do
  if echo "$FOUND" | grep -qx "$p"; then
    echo "FORBIDDEN permission present: $p"
    fail=1
  fi
done

for p in "${EXPECTED[@]}"; do
  if ! echo "$FOUND" | grep -qx "$p"; then
    echo "expected permission missing: $p"
    fail=1
  fi
done

# Anything outside the expected set is not necessarily wrong, but it must be a decision.
while read -r p; do
  [ -z "$p" ] && continue
  if ! printf '%s\n' "${EXPECTED[@]}" | grep -qx "$p"; then
    echo "unexpected permission, review it: $p"
    fail=1
  fi
done <<< "$FOUND"

if [ "$fail" -eq 0 ]; then
  echo "permissions OK ($VARIANT): $(echo "$FOUND" | wc -l | tr -d ' ') declared, no INTERNET"
fi
exit "$fail"
