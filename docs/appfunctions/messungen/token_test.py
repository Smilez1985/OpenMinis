#!/usr/bin/env python3
"""Compare afc with the plain appfunktionen skill: calls, bytes and time per task.
Measures what ends up in the model's context: command text + output.
Tokens = bytes / 3.6 (rough estimate, not a real tokenizer).

The raw transcripts in token_test_roh/ were produced with the command names of
the time (`--trocken`, `FEHLER`, ...); see ../GLOSSARY.md. The skill is a
separate, German-language tool: its subcommands `liste` (list), `rufen` (call)
and `schema` are called as it defines them."""
import subprocess, time, json, sys, os

AFC = '/tmp/afc-target/release/afc'
SK = 'python3 /var/minis/skills/appfunktionen/scripts/appfunktionen.py'
SKILL_MD = '/var/minis/skills/appfunktionen/SKILL.md'
OUT = '/var/minis/shared/openminis/token_test_roh'
os.makedirs(OUT, exist_ok=True)

def run(name, cmd):
    t = time.time()
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=300)
    dt = time.time() - t
    out = p.stdout + p.stderr
    open(f'{OUT}/{name}.txt', 'w').write(f'$ {cmd}\nrc={p.returncode}\n{out}')
    return dict(name=name, cmd_bytes=len(cmd.encode()), out_bytes=len(out.encode()),
                rc=p.returncode, seconds=round(dt, 1), success=(p.returncode == 0))

PKG = "com.samsung.android.app.contacts"
FN = "findPersons"
ok_args = '{"query":"amazon","maxCount":10}'
bad_args = '{"query":"amazon"}'                # maxCount missing (required field)
sk_ok = '{"findPersonsParams":{"query":"amazon","maxCount":10}}'
sk_bad = '{"findPersonsParams":{"query":"amazon"}}'

E = []
# Task 1: find the contact "amazon"
E.append(('1 find contact', 'afc', [run('1_afc_rufen', f"{AFC} call {PKG} {FN} --args '{ok_args}'")]))
E.append(('1 find contact', 'skill', [run('1_skill_liste', f"{SK} liste --paket {PKG}"),
                                      run('1_skill_rufen', f"{SK} rufen {PKG} {FN} '{sk_ok}'")]))
# Task 2: call with a missing required field
E.append(('2 required field missing', 'afc', [run('2_afc_pruefen', f"{AFC} call {PKG} {FN} --args '{bad_args}' --dry-run")]))
E.append(('2 required field missing', 'skill', [run('2_skill_rufen', f"{SK} rufen {PKG} {FN} '{sk_bad}'")]))
# Task 3: what does the function take?
E.append(('3 functions of the app', 'afc', [run('3_afc_show', f"{AFC} show com.samsung.android.contacts.person#PersonFunctions_findPersons")]))
E.append(('3 functions of the app', 'skill', [run('3_skill_schema', f"{SK} schema {PKG}")]))

instructions = {'afc': os.path.getsize('/var/minis/shared/openminis/repo/deps/afc/README.md'), 'skill': os.path.getsize(SKILL_MD)}
help_bytes = {'afc': len((lambda r: r.stdout + r.stderr)(subprocess.run(AFC + ' --help', shell=True, capture_output=True, text=True)).encode()),
              'skill': len(subprocess.run(SK + ' --help', shell=True, capture_output=True, text=True).stdout.encode())}

tok = lambda b: round(b / 3.6)
print(f"{'Task':26s}{'Path':7s}{'Calls':>6s}{'Cmd B':>7s}{'Out B':>8s}{'Sum~Tok':>10s}{'Sec':>6s}  Outcome")
res = []
for task, path, l in E:
    cb = sum(x['cmd_bytes'] for x in l); ob = sum(x['out_bytes'] for x in l)
    sec = round(sum(x['seconds'] for x in l), 1)
    outcome = 'hit' if any(x['success'] for x in l) else ('checked locally' if path == 'afc' and task.startswith('2') else 'no result')
    print(f"{task:26s}{path:7s}{len(l):>6d}{cb:>7d}{ob:>8d}{tok(cb+ob):>10d}{sec:>6.1f}  {outcome} (rc={[x['rc'] for x in l]})")
    res.append(dict(task=task, path=path, calls=len(l), command_bytes=cb, output_bytes=ob, tokens=tok(cb+ob), seconds=sec, outcome=outcome, rc=[x['rc'] for x in l]))
print('Instructions (once per session): afc README', instructions['afc'], 'B ~', tok(instructions['afc']), 'tok; skill SKILL.md', instructions['skill'], 'B ~', tok(instructions['skill']), 'tok')
print('--help: afc', help_bytes['afc'], 'B; skill', help_bytes['skill'], 'B')
json.dump(dict(results=res, instructions_bytes=instructions, help_bytes=help_bytes), open('/var/minis/shared/openminis/token_test.json', 'w'), indent=1)
