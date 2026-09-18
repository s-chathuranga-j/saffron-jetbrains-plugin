# Saffron for JetBrains IDEs

[![Build](https://github.com/s-chathuranga-j/saffron-jetbrains-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/s-chathuranga-j/saffron-jetbrains-plugin/actions/workflows/build.yml)

Language support for [Saffron](https://saffron-ai.lovable.app) `.saffron` files in IntelliJ IDEA, WebStorm, PyCharm, Rider and other JetBrains IDEs (2024.2+, Community editions included).

- **Highlighting** for `.saffron` (Gherkin + `StepSet`): bundled TextMate grammar, no setup.
- **Completion, go-to-definition, hover, diagnostics** for `.saffron` and `.feature`. Served by `saffron lsp` from the `saffron-ai` npm package, wired through [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) automatically.
- **Run configurations**: Run/Debug Configurations → Add New Configuration → **Saffron** (`run` with files, folders, tags, replay-only, headed, re-record and extra arguments; `report`; `accept`). Right-click a `.saffron` file or a folder of them → **Run**. Output in the Run tool window.
- **Saffron tool window** (right side), five tabs. **Files**: feature files with scenario counts, search, tick and Run Selected, Run All, Open Report, Accept All Proposals, last-run totals, pending proposals. **Proposals**: tick, read the narrative, the proof-replay verdict and the action-level diff of what the proposal changes, then Accept Selected / Reject Selected / Accept All. **Tags**: tick tags and Run Tagged. **Health**: vocabulary counts, divergent steps, near-duplicate wordings, effective config. **Dashboard**: the run report embedded in the IDE. The tabs (plugin 0.2.1) read `saffron status --json` from saffron-ai 0.5.4 or later.

## Use

1. Install **Saffron** from the JetBrains Marketplace (LSP4IJ is installed as a dependency).
2. In your project: `npm i -D saffron-ai` (once). The plugin runs the project-local `saffron lsp`; without the package it falls back to `npx saffron lsp` and shows a hint.
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

Source: https://github.com/s-chathuranga-j/saffron-jetbrains-plugin. Bug reports and questions: https://github.com/s-chathuranga-j/saffron-ai/issues (the shared Saffron tracker).

## License

Saffron Free Use License v1.0. See [LICENSE](LICENSE).
