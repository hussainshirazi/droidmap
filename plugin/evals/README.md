# Plugin evals

Tasks on a small notes app (`fixtures/notes`: Room at version 1, already "on Google Play", Hilt, Compose Navigation), run by
`claude plugin eval` with the droidmap plugin and without it, and graded on what a correct change has to contain:

| Case | The task | Graded |
|---|---|---|
| `room-add-column` | add a `pinned` flag to the `Note` entity | version bumped, `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT`, the migration registered and tested, the shipped schema file untouched, no destructive fallback |
| `rename-route-argument` | rename `note/{noteId}` to `notes/{id}` | the route and the `navigate` call renamed, and the ViewModel no longer reading `"noteId"` |
| `new-hilt-screen` | add a Stats screen with an injected ViewModel | the destination registered, a `@HiltViewModel` with an `@Inject` constructor, got with `hiltViewModel()` |

Run it from `plugin/` (it calls the model on your account, so it counts against your plan; each case runs 3 times in each arm):

```sh
P=mcp__plugin_droidmap_droidmap__droidmap_
claude plugin eval . --scaffold --trust-plugin --mocks off \
  --allow-tools Edit Write ${P}check ${P}impact ${P}diff ${P}explain ${P}find ${P}show --no-publish
```

`--scaffold` copies the fixture into each run's workspace (`fixtures/scaffold.sh`), and `--mocks off` starts the real droidmap
server, which downloads the droidmap release the plugin names; set `DROIDMAP_JAR=/path/to/droidmap.jar` to run a local build
instead. The agent gets no shell, so nothing is built.
