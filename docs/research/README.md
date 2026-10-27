# Research notes

Findings that were **measured or read at the source**, kept here so they can be
cited instead of re-derived. Each note names where a claim comes from and when
it was observed; claims that were not verified are marked as such.

| Note | What it answers |
|---|---|
| [appfunctions-formats.md](appfunctions-formats.md) | How an AppFunction declaration becomes something the OS can index — annotation, generated XML, naming, retention |
| [appfunctions-permissions-and-reach.md](appfunctions-permissions-and-reach.md) | Who may call AppFunctions, what that costs in permissions, and which apps on a real device expose them |

Related, under `docs/appfunctions/`: the design, roadmap and changelog of the
caller tool, plus the recovered schema field names.

## Why these notes exist

The AppFunctions surface moves between alpha releases and renames things
(the declaration annotation itself was renamed once). A note with a date and a
source survives that better than a recollection, and it keeps the design
documents free of measurement detail.
