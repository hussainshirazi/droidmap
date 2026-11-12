# room-schema-missing

Default: **warning**. A database exports its schema, but the `<version>.json` file of the current version, or of a version between the oldest exported one and the current one, is not in the project.

## Why it matters

Room writes one JSON file per database version into the schema folder. Those files are how migrations are tested (`MigrationTestHelper` creates the old database from them), how `AutoMigration` knows what changed, and how droidmap knows which versions users can still have. A missing file means a version whose migration cannot be tested or generated.

## How to fix

Point Room at a folder in the project: the Room Gradle plugin (`room { schemaDirectory("$projectDir/schemas") }`) or the KSP argument `room.schemaLocation`. Build the project, then commit the generated JSON files. Commit every version's file from now on, and never edit one that has shipped (see [room-schema-changed](room-schema-changed.md)).
