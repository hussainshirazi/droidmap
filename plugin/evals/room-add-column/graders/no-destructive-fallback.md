---
type: regex
target: { source: file, path: app/src/main/kotlin/com/example/notes/di/DataModule.kt }
match: not_contains
pattern: 'fallbackToDestructiveMigration'
---

Users' notes are never deleted by a destructive fallback.
