#!/usr/bin/env python3
"""Converts the measured registry (XML) into the line format that afc embeds.

Usage: registry_to_data.py <appfunctions-registry.xml> > deps/afc/data/registry.txt

Every entry carries its origin along: the tool shows it and validates against
it afterwards.

The XML was first written with German element and attribute names and German
values. Both spellings are read; the output always uses the English values
documented in the registry format (on/off/default-on/default-off, yes/no,
top-level, convention/assumed/call-ok/dump/error-message/schema).
"""
import sys, xml.etree.ElementTree as ET

# Old (German) value -> current value. Values that are already current pass through.
VALUES = {
    "an": "on", "aus": "off", "standard-an": "default-on", "standard-aus": "default-off",
    "ja": "yes", "nein": "no",
    "oberste-ebene": "top-level",
    "konvention": "convention", "vermutet": "assumed", "aufruf-ok": "call-ok",
    "fehlermeldung": "error-message",
    "liste-von-text": "list-of-text",
}


def attr(el, *names):
    """First attribute of `el` that exists under one of `names`, else None."""
    for n in names:
        if el.get(n) is not None:
            return el.get(n)
    return None


def val(v):
    return VALUES.get(v, v) if v is not None else None


def example_args(a):
    return None if a is None else a.replace('"_typ"', '"_type"').replace("<_id aus findFiles>", "<_id from findFiles>")


def row(*fields):
    return "\t".join("" if f is None else str(f).replace("\t", " ") for f in fields)


def main(path):
    root = ET.parse(path).getroot()
    print("# AppFunctions registry for afc. Generated from %s." % path)
    print("# F=function P=parameter D=field B=example E=meta. source= where the information comes from.")
    for app in root.iter("app"):
        for f in list(app.findall("function")) + list(app.findall("funktion")):
            fid = f.get("id")
            print(row("F", attr(app, "package", "paket"), fid, f.get("name"),
                      attr(f, "category", "kategorie"), val(f.get("status")),
                      val(attr(f, "verified", "geprueft")) or "no"))
            for p in list(f.findall("parameter")):
                ptype = (attr(p, "type", "typ") or "").replace("liste-von-text", "list-of-text")
                print(row("P", fid, p.get("name"), ptype,
                          val(attr(p, "level", "ebene")) or "", val(attr(p, "source", "quelle"))))
                for x in list(p.findall("field")) + list(p.findall("feld")):
                    print(row("D", fid, p.get("name"), x.get("name"), attr(x, "datatype", "datentyp"),
                              val(attr(x, "required", "pflicht")), val(attr(x, "source", "quelle"))))
            for b in list(f.findall("example")) + list(f.findall("beispiel")):
                print(row("B", fid, example_args(b.get("args")), val(attr(b, "source", "quelle")),
                          " ".join((b.text or "").split())))
    print(row("E", "device", attr(root, "device", "geraet") or "", "registry", root.get("version") or "", ""))


if __name__ == "__main__":
    main(sys.argv[1])
