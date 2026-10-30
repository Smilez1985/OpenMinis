#!/usr/bin/env python3
"""Wandelt die gemessene Registry (XML) in das Zeilenformat um, das afc einbettet.

Aufruf: registry_zu_daten.py <appfunctions-registry.xml> > deps/afc/data/registry.txt
Jede Angabe traegt ihre Herkunft mit: das Tool zeigt sie an und prueft danach.
"""
import sys, xml.etree.ElementTree as ET

def t(*felder):
    return "\t".join("" if f is None else str(f).replace("\t", " ") for f in felder)

def main(pfad):
    r = ET.parse(pfad).getroot()
    print("# AppFunctions-Registry fuer afc. Erzeugt aus %s." % pfad)
    print("# F=Funktion P=Parameter D=Feld B=Beispiel. quelle= woher die Angabe stammt.")
    for a in r.iter('app'):
        for f in a.findall('funktion'):
            fid = f.get('id')
            print(t("F", a.get('paket'), fid, f.get('name'), f.get('kategorie'), f.get('status'), f.get('geprueft') or "nein"))
            for p in f.findall('parameter'):
                print(t("P", fid, p.get('name'), p.get('typ'), p.get('ebene') or "", p.get('quelle')))
                for x in p.findall('feld'):
                    print(t("D", fid, p.get('name'), x.get('name'), x.get('datentyp'), x.get('pflicht'), x.get('quelle')))
            for b in f.findall('beispiel'):
                print(t("B", fid, b.get('args'), b.get('quelle'), " ".join((b.text or "").split())))
    print(t("E", "geraet", r.get('geraet') or "", "registry", r.get('version') or "", ""))

if __name__ == '__main__':
    main(sys.argv[1])
