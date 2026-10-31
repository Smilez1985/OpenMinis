#!/bin/sh
# Baut aus.jar aus src/ und att/. Braucht: javac (JDK 17) und r8.jar (D8) im selben Ordner.
# D8: https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar
# att/ sind Attrappen der Android-Klassen, damit javac sie kennt; sie kommen NICHT in die Jar.
# Ergebnis nach src/android/app/src/main/assets/appfunctions/aus.jar kopieren.
set -e
cd "$(dirname "$0")"
[ -f r8.jar ] || { echo "r8.jar fehlt (siehe Kopf dieser Datei)" >&2; exit 2; }
rm -rf .cls .cls2; mkdir .cls .cls2
javac -d .cls $(find att -name '*.java') src/Callback.java src/Ausfuehren.java src/Schalter.java src/SchalterCallback.java
cp .cls/Callback*.class .cls/Ausfuehren*.class .cls/Schalter*.class .cls2/
java -cp r8.jar com.android.tools.r8.D8 --min-api 35 --output aus.jar .cls2/*.class
rm -rf .cls .cls2
ls -l aus.jar; sha256sum aus.jar
