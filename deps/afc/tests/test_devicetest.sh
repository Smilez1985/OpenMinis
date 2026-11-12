#!/bin/sh
# Checks how the device test classifies results, using a fake afc: 1003 means
# "not present", not an error; real errors stay red; unconfirmed shapes count
# as deviations; without probes the test says nothing.
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d "${TMPDIR:-/tmp}/dt.XXXXXX") || exit 9
trap 'rm -rf "$T"' EXIT
cat > "$T/afc" <<'X'
#!/bin/sh
TAB=$(printf '\t')
if [ "$1" = probes ]; then
  case "$MODE" in
    none) exit 0 ;;
    deviation) printf 'com.x\tfindA\t{}\tno\ncom.google.y\tgetB\t{}\tyes\n' ;;
    *)         printf 'com.sec.a\tfindA\t{}\tyes\ncom.sec.b\tfindB\t{}\tyes\ncom.google.c\tgetC\t{}\tyes\n' ;;
  esac
  exit 0
fi
case "$MODE" in
  only1003) echo "ERROR 1003: Function not found"; exit 2 ;;
  mixed)
    case "$2" in
      com.sec.*) echo "ERROR 1003: Function not found"; exit 2 ;;
      *) echo "SUCCESS: {}"; exit 0 ;;
    esac ;;
  real) echo "ERROR 1000: Caller does not fit"; exit 2 ;;
  deviation) case "$2" in com.x) echo "ERROR 1001: Parameter"; exit 2 ;; *) echo "SUCCESS: {}"; exit 0 ;; esac ;;
  none) exit 0 ;;
esac
X
chmod +x "$T/afc"
fail=0
ok()  { echo "ok   $1"; }
bad() { echo "FAIL $1"; fail=1; }
run_mode() { AFC="$T/afc" MODE=$1 sh "$HERE/tools/devicetest.sh" >"$T/o" 2>&1; echo $?; }

rc=$(run_mode mixed)
[ "$rc" = 0 ] && rg -q 'Summary: 3 calls — 1 with content, 0 without content, 2 not present \(1003\), 0 deviations of unconfirmed shape, 0 errors' "$T/o" && ok "mixed: 1003 is not red, counts are right" || { bad "mixed rc=$rc"; cat "$T/o"; }
rc=$(run_mode only1003)
[ "$rc" = 4 ] && ok "all 1003: test says nothing (rc 4)" || { bad "only1003 rc=$rc"; cat "$T/o"; }
rc=$(run_mode real)
[ "$rc" = 1 ] && rg -q '3 errors' "$T/o" && ok "real error on a confirmed shape stays red" || { bad "real rc=$rc"; cat "$T/o"; }
rc=$(run_mode deviation)
[ "$rc" = 0 ] && rg -q '1 deviations' "$T/o" && ok "unconfirmed shape: deviation, not red" || { bad "deviation rc=$rc"; cat "$T/o"; }
rc=$(run_mode none)
[ "$rc" = 4 ] && ok "no probes: test says nothing (rc 4)" || { bad "none rc=$rc"; cat "$T/o"; }
exit $fail
