#!/usr/bin/env python3
"""Vergleich afc gegen Skill appfunktionen: Aufrufe, Bytes, Zeit je Aufgabe.
Misst, was in den Kontext des Modells wandert: Befehlstext + Ausgabe.
Token = Bytes/3.6 (grobe Schaetzung, keine echte Tokenisierung)."""
import subprocess, time, json, sys, os

AFC = '/tmp/afc-target/release/afc'
SK = 'python3 /var/minis/skills/appfunktionen/scripts/appfunktionen.py'
SKILL_MD = '/var/minis/skills/appfunktionen/SKILL.md'
OUT = '/var/minis/shared/openminis/token_test_roh'
os.makedirs(OUT, exist_ok=True)

def lauf(name, cmd):
    t = time.time()
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=300)
    dt = time.time() - t
    out = p.stdout + p.stderr
    open(f'{OUT}/{name}.txt', 'w').write(f'$ {cmd}\nrc={p.returncode}\n{out}')
    return dict(name=name, cmd_bytes=len(cmd.encode()), out_bytes=len(out.encode()),
                rc=p.returncode, sek=round(dt, 1), erfolg=(p.returncode == 0))

AR = "com.samsung.android.app.contacts"
FN = "findPersons"
ok_args = '{"query":"amazon","maxCount":10}'
bad_args = '{"query":"amazon"}'                # maxCount fehlt (Pflichtfeld)
sk_ok = '{"findPersonsParams":{"query":"amazon","maxCount":10}}'
sk_bad = '{"findPersonsParams":{"query":"amazon"}}'

E = []
# Aufgabe 1: Kontakt "amazon" finden
E.append(('1 Kontakt finden', 'afc', [lauf('1_afc_rufen', f"{AFC} call {AR} {FN} --args '{ok_args}'")]))
E.append(('1 Kontakt finden', 'skill', [lauf('1_skill_liste', f"{SK} liste --paket {AR}"),
                                       lauf('1_skill_rufen', f"{SK} rufen {AR} {FN} '{sk_ok}'")]))
# Aufgabe 2: Aufruf mit fehlendem Pflichtfeld
E.append(('2 Pflichtfeld fehlt', 'afc', [lauf('2_afc_pruefen', f"{AFC} call {AR} {FN} --args '{bad_args}' --trocken")]))
E.append(('2 Pflichtfeld fehlt', 'skill', [lauf('2_skill_rufen', f"{SK} rufen {AR} {FN} '{sk_bad}'")]))
# Aufgabe 3: was kann die Kontakte-App?
E.append(('3 Funktionen der App', 'afc', [lauf('3_afc_show', f"{AFC} show com.samsung.android.contacts.person#PersonFunctions_findPersons")]))
E.append(('3 Funktionen der App', 'skill', [lauf('3_skill_schema', f"{SK} schema {AR}")]))

anl = {'afc': os.path.getsize('/var/minis/shared/openminis/repo/deps/afc/README.md'), 'skill': os.path.getsize(SKILL_MD)}
hilfe = {'afc': len((lambda r: r.stdout + r.stderr)(subprocess.run(AFC + ' --help', shell=True, capture_output=True, text=True)).encode()),
         'skill': len(subprocess.run(SK + ' --help', shell=True, capture_output=True, text=True).stdout.encode())}

tok = lambda b: round(b / 3.6)
print(f"{'Aufgabe':24s}{'Weg':7s}{'Aufr.':>6s}{'Bef.B':>7s}{'Ausg.B':>8s}{'Summe~Tok':>10s}{'Sek':>6s}  Ergebnis")
res = []
for aufg, weg, l in E:
    cb = sum(x['cmd_bytes'] for x in l); ob = sum(x['out_bytes'] for x in l)
    sek = round(sum(x['sek'] for x in l), 1)
    erg = 'Treffer' if any(x['erfolg'] for x in l) else ('Pruefung lokal' if weg == 'afc' and aufg.startswith('2') else 'kein Ergebnis')
    print(f"{aufg:24s}{weg:7s}{len(l):>6d}{cb:>7d}{ob:>8d}{tok(cb+ob):>10d}{sek:>6.1f}  {erg} (rc={[x['rc'] for x in l]})")
    res.append(dict(aufgabe=aufg, weg=weg, aufrufe=len(l), befehl_bytes=cb, ausgabe_bytes=ob, tok=tok(cb+ob), sek=sek, ergebnis=erg, rc=[x['rc'] for x in l]))
print('Anleitung (einmal je Sitzung): afc README', anl['afc'], 'B ~', tok(anl['afc']), 'Tok; Skill SKILL.md', anl['skill'], 'B ~', tok(anl['skill']), 'Tok')
print('--help: afc', hilfe['afc'], 'B; skill', hilfe['skill'], 'B')
json.dump(dict(ergebnisse=res, anleitung_bytes=anl, hilfe_bytes=hilfe), open('/var/minis/shared/openminis/token_test.json', 'w'), indent=1)
