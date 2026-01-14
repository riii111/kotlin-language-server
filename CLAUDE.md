# Kotlin Language Server

This repository is a fork of `fwcd/kotlin-language-server`, customized for personal Neovim environment.
The official LSP is deprecated, so this fork will be maintained independently with bug fixes and improvements.

## Important Notes for Contributors

- PRs MUST be created against `riii111/kotlin-language-server`
- Do NOT create PRs to the upstream `fwcd/kotlin-language-server` (it's no longer maintained)
- When using `gh pr create`, specify `--repo riii111/kotlin-language-server`

## Technology Stack

| Technology | Purpose | Version |
|------------|---------|---------|
| Java | Runtime requirement | 11 (required) |
| Kotlin | Implementation language | 2.1.0 |
| Gradle | Build system | 8.12+ |
| LSP4J | LSP protocol implementation | 0.21.2 |
| Exposed ORM | Symbol index database | 0.37.3 |
| ktfmt | Code formatting | fwcd fork |
| FernFlower | Java decompilation | 243.22562.218 |

## Build Commands

| Command | Description |
|---------|-------------|
| `./gradlew :server:installDist` | Build binary distribution (recommended) |
| `./gradlew :server:distZip` | Create release ZIP |
| `./gradlew :server:test` | Run tests |
| `./gradlew :server:run` | Run standalone |
| `./gradlew :server:debugRun` | Debug mode (port 8000) |

## Configuration Example

```json
{
  "kotlin": {
    "compiler": { "jvm": { "target": "default" } },
    "indexing": { "enabled": true },
    "diagnostics": { "enabled": true, "debounceTime": 250 },
    "completion": { "snippets": { "enabled": true } },
    "externalSources": { "useKlsScheme": false, "autoConvertToKotlin": false }
  }
}
```

> **Note:** `compiler.jvm.target` defaults to `"default"`, which follows the build toolchain (Java 11).

## Editor Integration

Primary target: **Neovim** (via `nvim-lspconfig`)

Other supported editors: VSCode (vscode-kotlin), Emacs (lsp-mode), Helix

## Rules (Auto-loaded)

Detailed documentation is split into rule files under `.claude/rules/`:

- **architecture.md** - Project structure, modules, key classes
- **lsp-features.md** - Implemented LSP features, quick fixes
- **performance.md** - JVM tuning, large project recommendations
- **troubleshooting.md** - Common issues and solutions

## Version Info

- **Version:** 1.3.14
- **Kotlin:** 2.1.0
- **Java:** 11 (required)
- **Status:** Deprecated by upstream, actively maintained in this fork
