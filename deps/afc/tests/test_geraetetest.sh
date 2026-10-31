#!/bin/sh
# Prueft die Einstufung des Geraetetests mit einem falschen afc: 1003 ist
# "nicht vorhanden", kein Fehler; echte Fehler bleiben rot; unbestaetigte
# Formen zaehlen als Abweichung; ohne Proben sagt der Test nichts.
HIER=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d "${TMPDIR:-/tmp}/gt.XXXXXX") || exit 9
trap 'rm -rf "$T"' EXIT
cat > "$T/afc" <<'X'
#!/bin/sh
TAB=$(printf '\t')
if [ "$1" = proben ]; then
  case "$MODUS" in
    keine) exit 0 ;;
    abw)   printf 'com.x\tfindA\t{}\tnein\ncom.google.y\tgetB\t{}\tja\n' ;;
    *)     printf 'com.sec.a\tfindA\t{}\tja\ncom.sec.b\tfindB\t{}\tja\ncom.google.c\tgetC\t{}\tja\n' ;;
  esac
  exit 0
fi
case "$MODUS" in
  nur1003) echo "FEHLER 1003: Function not found"; exit 2 ;;
  gemischt)
    case "$2" in
      com.sec.*) echo "FEHLER 1003: Function not found"; exit 2 ;;
      *) echo "ERFOLG: {}"; exit 0 ;;
    esac ;;
  echt) echo "FEHLER 1000: Aufrufer passt nicht"; exit 2 ;;
  abw)  case "$2" in com.x) echo "FEHLER 1001: Parameter"; exit 2 ;; *) echo "ERFOLG: {}"; exit 0 ;; esac ;;
  keine) exit 0 ;;
esac
X
chmod +x "$T/afc"
fail=0
ok()  { echo "ok   $1"; }
bad() { echo "FAIL $1"; fail=1; }
lauf() { AFC="$T/afc" MODUS=$1 sh "$HIER/werkzeuge/geraetetest.sh" >"$T/o" 2>&1; echo $?; }

rc=$(lauf gemischt)
[ "$rc" = 0 ] && rg -q 'Bilanz: 3 Aufrufe — 1 mit Inhalt, 0 ohne Inhalt, 2 nicht vorhanden \(1003\), 0 Abweichungen unbestaetigter Form, 0 Fehler' "$T/o" && ok "gemischt: 1003 nicht rot, Zaehlung stimmt" || { bad "gemischt rc=$rc"; cat "$T/o"; }
rc=$(lauf nur1003)
[ "$rc" = 4 ] && ok "alles 1003: Test sagt nichts (rc 4)" || { bad "nur1003 rc=$rc"; cat "$T/o"; }
rc=$(lauf echt)
[ "$rc" = 1 ] && rg -q '3 Fehler' "$T/o" && ok "echter Fehler bei bestaetigter Form bleibt rot" || { bad "echt rc=$rc"; cat "$T/o"; }
rc=$(lauf abw)
[ "$rc" = 0 ] && rg -q '1 Abweichungen' "$T/o" && ok "unbestaetigte Form: Abweichung, nicht rot" || { bad "abw rc=$rc"; cat "$T/o"; }
rc=$(lauf keine)
[ "$rc" = 4 ] && ok "keine Proben: Test sagt nichts (rc 4)" || { bad "keine rc=$rc"; cat "$T/o"; }
exit $fail
