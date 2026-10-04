# Saffron for JetBrains IDEs

[![Build](https://github.com/s-chathuranga-j/saffron-jetbrains-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/s-chathuranga-j/saffron-jetbrains-plugin/actions/workflows/build.yml)

Language support for [Saffron](https://saffron-ai.io) `.saffron` files in IntelliJ IDEA, WebStorm, PyCharm, Rider and other JetBrains IDEs (2024.2+, Community editions included).

- **Highlighting** for `.saffron`, Saffron's own test language (keywords, `StepSet` definitions and invocations, `{env:...}`, `{data:...}` and `{unique:...}` tokens, tables, doc strings): bundled TextMate grammar, no setup.
- **Completion, go-to-definition, hover, diagnostics** for `.saffron` and `.feature`. Served by `saffron lsp` from the `saffron-ai` npm package, wired through [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) when it is installed (an optional dependency since 0.2.2, so the plugin also installs on IDE builds LSP4IJ has not reached yet).
- **Run configurations**: Run/Debug Configurations → Add New Configuration → **Saffron** (`run` with files, folders, tags, replay-only, headed, re-record and extra arguments; `report`; `accept` and `reject`, taking proposal files, blank for every one; `trace`, which opens the execution replay of a scenario: a scenario name or `feature:scenario`, blank for every traced one; `prune`, which lists orphaned caches and proposals, deleting them only with `--yes` in extra arguments; `login`, taking a provider name, blank for the configured one). Right-click a `.saffron` file or a folder of them → **Run**. Output in the Run tool window.
- **Saffron tool window** (right side), eight tabs. **Files**: feature files with scenario counts, search, tick and Run Selected, Run All, Open Report, Accept All Proposals, last-run totals, pending proposals. **Last Run**: what failed or healed in the last run, with the failed step; double-click opens the screenshot of the page at that moment (a screenshot a later run replaced is refused, not shown as this run's), and **Open Replay** runs `saffron trace` for the scenario, opening its execution replay in your browser (needs `"trace": "retain-on-failure"` or `"on"` in the config; with no row selected, as after a run where everything passed, it opens every scenario the run traced). **Open Report** opens the run's HTML report in the browser. Open Replay and the replaced-screenshot check need saffron-ai 0.9.0 or later; with an older runner, screenshots open unchecked. **Proposals**: tick, read the narrative, the proof-replay verdict and the action-level diff of what the proposal changes, then Accept Selected / Reject Selected / Accept All; a proposal imported from CI names the run it came from. **CI Runs**: the current branch's GitHub Actions or Azure Pipelines runs, from `saffron runs`; **Import Run** runs `saffron import --run`, which downloads the run's Saffron bundle and checks it against your checkout, and lists under the run what happened to each proposal (imported, already here, or not imported with the reason). The CI's own tool (gh, or az with its azure-devops extension) keeps the credentials. CI Runs need saffron-ai 0.9.3 or later; with an older runner the tab says which version the project has. The full guide, with the pipeline setup: [CI runs in your IDE](https://saffron-ai.io/docs/ci-runs-in-your-ide). **Tags**: tick tags and Run Tagged. **Health**: vocabulary counts, divergent steps, near-duplicate wordings, effective config. **Orphans**: caches and pending proposals whose scenario no longer exists, with the reason; double-click to open one, Remove All to run `saffron prune --yes` after a confirmation. **Dashboard**: the suite's quality page that `saffron dashboard` renders, in the run report's design and the same as in VS Code: tests, passing share, failing (and for how many runs in a row), flaky scenarios, scenarios healed again and again, never run and left out of the last run, slowest, pass rate and execution time over the last 20 runs, and results per tag; click a scenario's name to open it (needs saffron-ai 0.9.4 or later). The tabs (plugin 0.2.1 and later) read `saffron status --json` from saffron-ai 0.5.4 or later.

## Use

1. Install **Saffron** from the JetBrains Marketplace, and **LSP4IJ** for completion, go-to-definition and diagnostics.
2. In your project: `npm i -D saffron-ai` (once). The plugin runs the project-local `saffron lsp`; without the package it falls back to `npx -y -p saffron-ai saffron lsp` and shows a hint. (A bare `npx saffron` would fetch an unrelated npm package named `saffron`.)
3. Open a `.saffron` file.

## Develop

```bash
./gradlew buildPlugin        # → build/distributions/saffron-jetbrains-<version>.zip
./gradlew runIde             # sandbox IDE with the plugin
./gradlew verifyPlugin       # JetBrains plugin verifier against recommended IDEs
JETBRAINS_MARKETPLACE_TOKEN=... ./gradlew publishPlugin
```

The grammar under `src/main/resources/textmate/` is the same one shipped in `saffron-vscode` and in the `saffron-ai` package (`textmate/saffron/`); keep the three in sync when it changes.

## Source and issues

Source: https://github.com/s-chathuranga-j/saffron-jetbrains-plugin. Bug reports and questions: https://github.com/s-chathuranga-j/saffron-community/issues (the shared Saffron tracker).

## License

Saffron Free Use License v1.0. See [LICENSE](LICENSE).
