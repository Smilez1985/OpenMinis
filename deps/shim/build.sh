#!/bin/sh
# Builds shim.jar from src/ and stubs/. Needs: javac (JDK 17) and r8.jar (D8) in this directory.
# D8: https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar
# stubs/ are stand-ins for the Android classes so that javac knows them; they are NOT packaged into the jar.
# Copy the result to src/android/app/src/main/assets/appfunctions/shim.jar.
set -e
cd "$(dirname "$0")"
[ -f r8.jar ] || { echo "r8.jar is missing (see the header of this file)" >&2; exit 2; }
rm -rf .cls .cls2; mkdir .cls .cls2
javac -d .cls $(find stubs -name '*.java') src/Callback.java src/Execute.java src/Toggle.java src/ToggleCallback.java
cp .cls/Callback*.class .cls/Execute*.class .cls/Toggle*.class .cls2/
java -cp r8.jar com.android.tools.r8.D8 --min-api 35 --output shim.jar .cls2/*.class
rm -rf .cls .cls2
ls -l shim.jar; sha256sum shim.jar
