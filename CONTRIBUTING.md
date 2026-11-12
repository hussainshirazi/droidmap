# Contributing to droidmap

Bug reports, false positives, ideas and pull requests are all welcome. Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).

## Reporting a problem

- **A wrong finding** (droidmap flags correct code) or **a missed one**: use the *False positive or missed problem* issue form. A small snippet that reproduces it helps most; the rule id is on each finding (`droidmap explain <rule>` describes it).
- **A crash or wrong output**: use the *Bug report* form, with `droidmap --version` and the command you ran.
- **A security problem**: see [SECURITY.md](SECURITY.md), not a public issue.

## Building and testing

Java 17 or newer.

```sh
./gradlew test fatJar                        # the tests, and build/libs/droidmap.jar
java -jar build/libs/droidmap.jar check path/to/an/android/project
./gradlew fatJar && ./gradlew -p gradle-plugin test   # the Gradle plugin's tests
```

## Where things are

The code lives in `src/main/kotlin/droidmap`:

- `KotlinReader` and `JavaReader` read each file into one language-neutral model (`Model.kt`).
- The extractors in `extract/` (Gradle, Room, Navigation, Hilt, Koin, WorkManager, the manifest, R8 and Gson) turn the model into the graph.
- The rules are in `Checks.kt`, with a page each in `docs/rules/` (shown by `droidmap explain` and in SARIF).
- Queries are in `Query.kt`, `diff` in `Diff.kt`, the call index for tests in `Calls.kt`, the report in `Report.kt` and the MCP server in `Mcp.kt`.
- `gradle-plugin/` is the Gradle plugin (Java, so it runs on Gradle 8 and 9), `plugin/` the Claude Code plugin.

## Pull requests

- A new rule or a fixed false positive comes with a case in `CasesTest`: a small project written in the test, and what droidmap should find in it.
- A new rule gets a page in `docs/rules/<id>.md` and a row in the README's rules table.
- CI also runs droidmap on open-source apps at fixed commits ([realworld/projects.txt](realworld/projects.txt)) and fails when their findings change. If the change is right, update `realworld/expected/` with what `realworld/check.sh` prints, and say why in the pull request.
- droidmap only reads files: it never runs Gradle or the project's code and never sends anything anywhere. Changes need to keep it that way.

By contributing you agree that your contribution is licensed under the project's [Apache 2.0 license](LICENSE).
