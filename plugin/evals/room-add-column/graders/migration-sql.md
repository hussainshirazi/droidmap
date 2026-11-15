---
type: regex
target: trace
flags: i
pattern: 'ALTER(\s|\\n)+TABLE(\s|\\n)+[`"\\]*notes[`"\\]*(\s|\\n)+ADD(\s|\\n)+COLUMN(\s|\\n)+[`"\\]*pinned[`"\\]*(\s|\\n)+INTEGER(\s|\\n)+NOT(\s|\\n)+NULL(\s|\\n)+DEFAULT|defaultValue(\s|\\n)*=(\s|\\n)*\\?"(0|false)'
---

The new NOT NULL column gets a default: in the migration SQL (`ADD COLUMN ... NOT NULL DEFAULT`) or, for an AutoMigration, `@ColumnInfo(defaultValue = "0")`. Without one, users on version 1 crash. (Matched in the code the agent wrote; `\\n` is a newline in the trace's JSON.)
