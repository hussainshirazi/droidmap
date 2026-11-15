---
type: regex
target: { source: file, path: app/src/main/kotlin/com/example/notes/ui/NoteScreen.kt }
match: not_contains
pattern: '"noteId"'
---

NoteViewModel no longer reads "noteId" from its SavedStateHandle (the argument is now called id, so that key would always be null and the screen would crash).
