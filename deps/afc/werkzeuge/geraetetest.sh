#!/bin/sh
# Geraetetest fuer afc: ruft die LESENDEN Funktionen auf, die die Registry
# DIESES Geraets hergibt (`afc proben`; mit `afc refresh` aufgefrischt, sonst
# die eingebettete). Keine feste Liste: welche Apps es gibt, bestimmt das Geraet.
# Samsung-Apps sind kein Massstab; ein Geraet ohne sie hat eben weniger Proben.
#
#   werkzeuge/geraetetest.sh [--alles]
#
# Ausgabe: je Funktion eine Zeile mit ERFOLG / LEER / FEHLER, am Ende eine Bilanz.
# Der Test ruft nichts Schreibendes auf. Serial, weil parallele Aufrufe auf
# diesem Geraet 5 s statt 1 s brauchen (MESSUNGEN.md).
HIER=$(cd "$(dirname "$0")/.." && pwd)
AFC="${AFC:-$HIER/target/release/afc}"
[ -x "$AFC" ] || { echo "afc fehlt: erst bauen (cargo build --release)" >&2; exit 3; }
TMPDIR=/tmp
export TMPDIR
AUSGABE=$(mktemp)
ANZAHL=0; GRUEN=0; LEER=0; ROT=0; FEHLT=0; ABW=0

lauf() {
  paket=$1; name=$2; args=$3; best=$4
  printf '%-30s ' "$name"
  antwort=$("$AFC" call "$paket" "$name" --args "$args" 2>&1)
  ANZAHL=$((ANZAHL+1))
  printf '%s' "$antwort" >> "$AUSGABE"
  case "$antwort" in
    *"FEHLER 1003"*)  FEHLT=$((FEHLT+1)); echo "FEHLT   (nicht auf diesem Geraet, 1003)" ;;
    *"EMPTY_RESULT"*) LEER=$((LEER+1));   echo "LEER    (kein Treffer nachgewiesen)" ;;
    *"ERFOLG:"*)      GRUEN=$((GRUEN+1)); echo "ERFOLG" ;;
    *)
      if [ "$best" = ja ]; then
        ROT=$((ROT+1)); echo "FEHLER  $(printf '%s' "$antwort" | tail -1)"
      else
        ABW=$((ABW+1)); echo "ABWEICHUNG (Form nie bestaetigt)  $(printf '%s' "$antwort" | tail -1)"
      fi ;;
  esac
}

PROBEN=$("$AFC" proben) || { echo "afc proben schlug fehl" >&2; exit 3; }
[ -n "$PROBEN" ] || { echo "Die Registry liefert keine Proben: nichts zu testen." >&2; exit 4; }
TAB=$(printf '\t')
while IFS="$TAB" read -r paket name args best; do
  lauf "$paket" "$name" "$args" "$best"
done <<EOF2
$PROBEN
EOF2

echo
echo "Bilanz: $ANZAHL Aufrufe — $GRUEN mit Inhalt, $LEER ohne Inhalt, $FEHLT nicht vorhanden (1003), $ABW Abweichungen unbestaetigter Form, $ROT Fehler."
[ "$FEHLT" -eq 0 ] || echo "Hinweis: 1003 ist kein Fehler des Weges, sondern die richtige Antwort fuer eine App, die hier nicht installiert ist."
[ "$ABW" -eq 0 ] || echo "Hinweis: Abweichungen betreffen Formen, die nie auf dem Messgeraet liefen; sie zaehlen nicht als rot."
echo "Vollstaendige Antworten: $AUSGABE"
[ "$ANZAHL" -gt "$FEHLT" ] || { echo "Nichts gelaufen: alle $ANZAHL Aufrufe 1003 - der Test sagt hier nichts." >&2; exit 4; }
[ "$ROT" -eq 0 ]
