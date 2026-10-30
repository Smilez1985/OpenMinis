#!/bin/sh
# Geraetetest fuer afc: ruft die in der Registry bestaetigten (geprueft="ja")
# LESENDEN Funktionen auf und vergleicht die Antwort mit dem, was am 29./30.10.
# gemessen wurde.
#
#   werkzeuge/geraetetest.sh [--alles]
#
# Ausgabe: je Funktion eine Zeile mit ERFOLG / LEER / FEHLER, am Ende eine Bilanz.
# Der Test ruft nichts Schreibendes auf. Serial, weil parallele Aufrufe auf
# diesem Geraet 5 s statt 1 s brauchen (MESSUNGEN.md).
HIER=$(cd "$(dirname "$0")/.." && pwd)
AFC="$HIER/target/release/afc"
[ -x "$AFC" ] || { echo "afc fehlt: erst bauen (cargo build --release)" >&2; exit 3; }
TMPDIR=/tmp
export TMPDIR
AUSGABE=$(mktemp)
ANZAHL=0; GRUEN=0; LEER=0; ROT=0

lauf() {
  name=$1; paket=$2; args=$3; soll=$4
  printf '%-26s ' "$name"
  antwort=$("$AFC" call "$paket" "$name" --args "$args" 2>&1)
  ANZAHL=$((ANZAHL+1))
  printf '%s' "$antwort" >> "$AUSGABE"
  case "$antwort" in
    *"EMPTY_RESULT"*) KLASSE=LEER;  LEER=$((LEER+1));  echo "LEER   (kein Treffer nachgewiesen)" ;;
    *"ERFOLG:"*)      KLASSE=INHALT; GRUEN=$((GRUEN+1)); echo "ERFOLG" ;;
    *)                KLASSE=FEHLER; ROT=$((ROT+1)); echo "FEHLER  $(printf '%s' "$antwort" | tail -1)" ;;
  esac
  case "$soll" in
    leer)   [ "$KLASSE" = LEER ]   || echo "   ^ Abweichung: am 29.10. kam hier eine leere Liste" ;;
    inhalt) [ "$KLASSE" = INHALT ] || echo "   ^ Abweichung: am 29.10. kam hier Inhalt" ;;
  esac
}

# Die 12 bestaetigten lesenden Funktionen (die 13. ist rejectCall, schreibend).
lauf getDeviceStateMetadata com.google.android.gms '{}'
lauf getUncategorizedDeviceState com.google.android.gms '{}'
lauf findPersons com.samsung.android.app.contacts '{"query":"e","maxCount":3}'
lauf findMediaItems com.sec.android.gallery3d '{"maxCount":2}'
lauf findAlbums com.sec.android.gallery3d '{"query":"a","maxCount":5}'
lauf findBookmarks com.sec.android.app.sbrowser '{"query":"a","maxCount":3}'
lauf findCallRecords com.samsung.android.dialer '{"query":"a","maxCount":3}' leer
lauf findFiles com.sec.android.app.myfiles '{"query":"pdf","maxCount":5}' leer
lauf getPersons com.samsung.android.app.contacts '{"personIds":["73"]}'
lauf getPersons com.samsung.android.dialer '{"personIds":["73"]}'

echo
echo "Bilanz: $ANZAHL Aufrufe — $GRUEN mit Inhalt, $LEER ohne Inhalt, $ROT Fehler."
echo "Vollstaendige Antworten: $AUSGABE"
[ "$ROT" -eq 0 ]
