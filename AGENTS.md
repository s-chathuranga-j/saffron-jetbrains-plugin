# Saffron for JetBrains IDEs

The IntelliJ Platform plugin for Saffron, the `saffron-ai` test runner
(https://www.npmjs.com/package/saffron-ai). It runs the runner's CLI and
its language server and shows the results in the Saffron tool window, run
configurations and the editor.

## Layout and commands

- `src/main/kotlin/ai/saffron/jetbrains/`: the plugin (`run/`, `ui/`, ...)
- `src/test/kotlin/`: tests

```bash
./gradlew test --offline -q
./gradlew buildPlugin        # build/distributions/*.zip
./gradlew runIde             # a sandbox IDE with the plugin
```

Drop `--offline` on a first build, before Gradle has its dependencies
cached. If Gradle reports a test that no longer exists, delete
`build/instrumented` and `build/test-results` and run again.

## Rules

- The plugin mirrors the runner. For anything the runner decides (the test
  folder and which files it runs, project paths, `{data:...}` references,
  `As "..."` sessions, the JSON its commands print), match the runner's
  behavior rather than re-deriving the rule.
- Keep disk and process work off the UI thread.
- Every fix comes with a test, and every user-visible change with an entry
  at the top of `CHANGELOG.html`.
- User-facing text uses plain punctuation, without em dashes.
- The code is public to read under the Saffron Free Use License
  (`LICENSE`).
