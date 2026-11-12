#!/bin/sh
# Device test for afc: calls the READ-ONLY functions that the registry of THIS
# device offers (`afc probes`; refreshed with `afc refresh`, otherwise the
# embedded one). There is no fixed list: the device decides which apps exist.
# Samsung apps are not a yardstick; a device without them simply has fewer probes.
#
#   tools/devicetest.sh
#
# Output: one line per function with SUCCESS / EMPTY / ERROR, a summary at the end.
# The test never calls anything that writes. It runs serially because parallel
# calls on the measuring device took 5 s instead of 1 s (MESSUNGEN.md).
HERE=$(cd "$(dirname "$0")/.." && pwd)
AFC="${AFC:-$HERE/target/release/afc}"
[ -x "$AFC" ] || { echo "afc missing: build it first (cargo build --release)" >&2; exit 3; }
TMPDIR=/tmp
export TMPDIR
OUTPUT=$(mktemp)
COUNT=0; GREEN=0; EMPTY=0; RED=0; MISSING=0; DEVIATION=0

run_probe() {
  package=$1; name=$2; args=$3; confirmed=$4
  printf '%-30s ' "$name"
  reply=$("$AFC" call "$package" "$name" --args "$args" 2>&1)
  COUNT=$((COUNT+1))
  printf '%s' "$reply" >> "$OUTPUT"
  case "$reply" in
    *"ERROR 1003"*)   MISSING=$((MISSING+1)); echo "MISSING (not on this device, 1003)" ;;
    *"EMPTY_RESULT"*) EMPTY=$((EMPTY+1));     echo "EMPTY   (no hit proven)" ;;
    *"SUCCESS:"*)     GREEN=$((GREEN+1));     echo "SUCCESS" ;;
    *)
      if [ "$confirmed" = yes ]; then
        RED=$((RED+1)); echo "ERROR   $(printf '%s' "$reply" | tail -1)"
      else
        DEVIATION=$((DEVIATION+1)); echo "DEVIATION (shape never confirmed)  $(printf '%s' "$reply" | tail -1)"
      fi ;;
  esac
}

PROBES=$("$AFC" probes) || { echo "afc probes failed" >&2; exit 3; }
[ -n "$PROBES" ] || { echo "The registry offers no probes: nothing to test." >&2; exit 4; }
TAB=$(printf '\t')
while IFS="$TAB" read -r package name args confirmed; do
  run_probe "$package" "$name" "$args" "$confirmed"
done <<EOF2
$PROBES
EOF2

echo
echo "Summary: $COUNT calls — $GREEN with content, $EMPTY without content, $MISSING not present (1003), $DEVIATION deviations of unconfirmed shape, $RED errors."
[ "$MISSING" -eq 0 ] || echo "Note: 1003 is not a failure of the route but the correct answer for an app that is not installed here."
[ "$DEVIATION" -eq 0 ] || echo "Note: deviations concern shapes that never ran on the measuring device; they do not count as red."
echo "Full replies: $OUTPUT"
[ "$COUNT" -gt "$MISSING" ] || { echo "Nothing ran: all $COUNT calls returned 1003 - the test says nothing here." >&2; exit 4; }
[ "$RED" -eq 0 ]
