---
type: regex
target: { source: file, path: app/src/main/kotlin/com/example/notes/ui/NotesNavHost.kt }
match: not_contains
pattern: '"note/'
---

Nothing navigates to the old route any more.
