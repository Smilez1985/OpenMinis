# Test log 5 — what the model has to read and send: `afc` against the plain skill (30.10.2026)

Question: does the `afc` path cost the model fewer tokens than the plain
`appfunktionen` skill without `afc`?

Method: three tasks, each run once through both paths on the same device.
Measured is what reaches the model's context: the command text it sends plus
the output it gets back. **Tokens are estimated as bytes / 3.6** — a rough rule,
not a tokenizer; compare the two columns with each other, not with a bill.
Script and raw outputs: [messungen/token_test.py](messungen/token_test.py),
[messungen/token_test_roh/](messungen/token_test_roh/),
[messungen/token_test.json](messungen/token_test.json).

## 1. The result that shapes everything: the skill does not run here

The skill calls `cmd app_function`. On this device that command does not exist
(`No shell command implementation.`, with Shizuku as root and as uid 2000). The
skill therefore fails at its first step, `list-app-functions lieferte nichts`
(rc 2), for `liste` **and** for `rufen`. So the plain skill returns no contact
here, at any token price. `afc` goes through the binder and does.

This is a difference in **whether it works**, not only in what it costs.

Where the skill is known to run, from the maintainer: on a device with
Android 17, where `cmd app_function` exists. On a custom ROM based on
Android 16 it fails as above. A second Android 16 device with another custom ROM
exists, and the skill has **not been tried there**; whether `cmd app_function`
exists on it is open.

## 2. Per task

| Task | Path | Calls | Sent (B) | Received (B) | ~Tokens | Outcome |
|---|---|---:|---:|---:|---:|---|
| 1 find the contact "amazon" | `afc` | 1 | 119 | 920 | 289 | hit (1 contact) |
| 1 find the contact "amazon" | skill | 2 | 283 | 86 | 102 | no result, rc 2 twice |
| 2 required field missing | `afc` | 1 | 115 | 235 | 97 | refused locally, names the field |
| 2 required field missing | skill | 1 | 158 | 43 | 56 | no result, rc 2 (not the field error) |
| 3 what does the function take | `afc` | 1 | 96 | 666 | 212 | parameters, types, required, origin |
| 3 what does the function take | skill | 1 | 104 | 1459 | 434 | schema file as JSON |

Reading it honestly:

- **Task 1:** the skill is cheaper per row (102 against 289 tokens) only
  because it returned an error text instead of an answer. A path that fails
  quickly is not cheaper in any useful sense. No fair token comparison exists
  for this task on this device.
- **Task 2:** `afc` costs more (97 against 56) and gives the useful answer:
  `FEHLER P1: Pflichtfeld 'maxCount' (long) fehlt`, without calling the device.
  The skill's 56 tokens are the same dead-end message as in task 1.
- **Task 3:** the only like-for-like row. Both return the parameters;
  `afc` needs **212** tokens, the skill **434** — about half. `afc` states
  required fields and where each fact comes from; the skill returns the whole
  schema JSON including empty descriptions.

## 3. What has to be read once per session

| | Bytes | ~Tokens |
|---|---:|---:|
| `afc` README (not required to use it) | 4328 | 1202 |
| `afc --help` | 750 | 208 |
| skill `SKILL.md` (loaded by the skill mechanism) | 5140 | 1428 |
| skill `--help` | 995 | 276 |

The instruction cost is of the same size on both sides. `afc --help` is enough
to start; the README is optional.

## 4. Verdict

- Measured: **`afc` is cheaper for task 3 (212 against 434 tokens)**; for tasks
  1 and 2 the skill is cheaper only because it fails. The sum over the table
  (afc 598, skill 592) is **not** a comparison and must not be quoted as one.
- Measured: the skill does not work on this device; `afc` does.
- **Not measured:** the skill on a device where `cmd app_function` exists (the
  Android 17 device, and the untested second Android 16 ROM). There
  it would return answers; its cost per call would then be that of task 3
  (schema in full) and of the raw reply, and the comparison has to be repeated.
- Contact data in the raw output of task 1 (name, number, lookup key, id) is
  blacked out with `X` of the same length, so the byte counts stay true.
- **Not measured:** real token counts. The factor 3.6 is an assumption.
- **Not measured:** the effect of the checks over a session. A refusal before
  the call (task 2) saves a failed round trip; how often that happens in
  practice is not known from three runs.
- One run per cell. Timings (0.0–1.0 s) are too small to rank.

## 5. Standing cost of the tool definition (31.10.)

Section 3 counted what is read once. The **current build** of the app sends the
definition of every enabled tool with each request (`AgentTools.kt` rebuilds
the list every turn). Counted from the sources with the same estimate
(bytes / 3.6):

| | Bytes | ~Tokens | When paid |
|---|---:|---:|---|
| native tool: all strings of the definition | 1737 | 482 | every request while the switch is on |
| of which `description` | 1046 | 291 | same |
| skill: entry in the skills list | 668 | 186 | every request |
| skill: `SKILL.md` | 5573 | 1548 | once per session, when the skill is loaded |

Reading it:

- This is a property of **how the app passes tools today**, not of a native
  tool. A provider API is stateless, so a tool that is absent from the request
  cannot be called; but a tool may be sent as name and one line, with the
  description and parameters fetched on demand. That is a decision for the
  whole app (see DESIGN), not for this feature alone.
- For this tool alone the definition can shrink: `list` and `schema` already
  return what the long `description` repeats.
- The 482 are the strings only; the schema around them was not counted.
  Provider prompt caching, which lowers repeated cost, was not measured.
- What argues for the native tool is section 1 (the skill does not run without
  `cmd app_function`) and the per-call result of task 3 (212 against 434).
- **Not measured:** the Kotlin path (not compiled yet).

## 6. After shortening (31.10.)

The definition of `app_functions` was cut to name, one line and short parameter
texts; the how-to moved into the answer of `list` (sent once per use, not per
request). Strings of `definition()`: **730 B, ~203 tokens**, against 1737 B,
~482 before (same estimate). Saved per request: ~279 tokens. The `list` answer
grows by about 800 B once per use. **Not measured:** whether models still use
the tool correctly with the short text; that needs a run in the app.
