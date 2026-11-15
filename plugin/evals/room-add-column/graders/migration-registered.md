---
type: regex
target: trace
pattern: 'addMigrations\(\s*[A-Za-z*]|autoMigrations(\s|\\n)*=(\s|\\n)*\[(\s|\\n)*AutoMigration'
---

The migration is passed to the database builder (`addMigrations(...)`), or declared as an AutoMigration on @Database.
