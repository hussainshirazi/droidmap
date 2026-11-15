---
type: regex
target: { source: file, path: app/schemas/com.example.notes.data.AppDatabase/1.json }
match: not_contains
pattern: 'pinned'
---

The schema file of version 1, which has shipped, is left as it is.
