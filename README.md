# Saffron for JetBrains IDEs

Language support for [Saffron](https://saffron-ai.lovable.app) `.saffron` files in IntelliJ IDEA, WebStorm, PyCharm, Rider and other JetBrains IDEs (2024.2+, Community editions included).

- **Highlighting** for `.saffron` (Gherkin + `StepSet`) — bundled TextMate grammar, no setup.
- **Completion, go-to-definition, hover, diagnostics** for `.saffron` and `.feature` — served by `saffron lsp` from the `saffron-ai` npm package, wired through [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) automatically.

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

## License

Saffron Free Use License v1.0 — see [LICENSE](LICENSE).
