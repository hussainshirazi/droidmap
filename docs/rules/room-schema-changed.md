# room-schema-changed

Default: **warning**. Only with `--base REF`: the schema JSON of a version that already existed at `REF` is different now.

## Why it matters

Once a version has shipped, phones have databases with exactly that schema. If the code now describes version 4 differently, those phones fail Room's integrity check (`Room cannot verify the data integrity`) and crash on launch. It usually happens when an entity changes and the build quietly regenerates the existing version's file.

## How to fix

Restore the old JSON file, bump the database version, and add a migration for the change. If the version has never shipped, the change is expected and you can ignore the finding.

The most precise base is your last release tag (`droidmap check --base v1.4.0`), since only a released schema must never change. The GitHub Action compares with the pull request's base branch.
