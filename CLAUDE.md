# Kotlin Language Server - Project Documentation

This repository is a fork of `fwcd/kotlin-language-server`, customized for personal Neovim environment.
The official LSP is deprecated, so this fork will be maintained independently with bug fixes and improvements.

## Important Notes for Contributors

- PRs MUST be created against `riii111/kotlin-language-server`
- Do NOT create PRs to the upstream `fwcd/kotlin-language-server` (it's no longer maintained)
- When using `gh pr create`, specify `--repo riii111/kotlin-language-server`

---

## Architecture Overview

### Project Structure

```
kotlin-language-server/
├── server/                    # Main LSP implementation
│   ├── src/main/kotlin/org/javacs/kt/
│   ├── src/test/kotlin/       # Comprehensive test suite
│   └── build.gradle.kts       # Application configuration
├── shared/                    # Shared utilities (classpath, database, utils)
│   └── src/main/kotlin/org/javacs/kt/
├── platform/                  # Dependency management (BOM)
├── buildSrc/                  # Custom Gradle plugins
└── gradle.properties          # Build properties (version=1.3.14)
```

### Technology Stack

| Technology | Purpose | Version |
|------------|---------|---------|
| Java | Runtime requirement | 11 (required) |
| Kotlin | Implementation language | 2.1.0 |
| Gradle | Build system | 8.12+ |
| LSP4J | LSP protocol implementation | 0.21.2 |
| Exposed ORM | Symbol index database | 0.37.3 |
| ktfmt | Code formatting | fwcd fork |
| FernFlower | Java decompilation | 243.22562.218 |

---

## Module Structure

### Server Module (`server/`)

**Core Files:**
| File | Responsibility |
|------|----------------|
| `Main.kt` | Entry point (stdio/TCP Server/TCP Client modes) |
| `KotlinLanguageServer.kt` | LSP LanguageServer interface implementation |
| `KotlinTextDocumentService.kt` | Text document operations (completion, hover, etc.) |
| `KotlinWorkspaceService.kt` | Workspace operations (symbol search, commands) |
| `Configuration.kt` | Initialization options configuration |
| `CompiledFile.kt` | Incremental compilation management |
| `CompilerClassPath.kt` | Kotlin compiler setup |
| `SourcePath.kt` | Source file cache and management |

### Shared Module (`shared/`)

| Directory | Purpose |
|-----------|---------|
| `classpath/` | Maven/Gradle/Shell classpath resolution |
| `database/` | Exposed ORM for symbol index DB |
| `util/` | Async execution, shell commands, URI handling |

**Classpath Resolution Chain:**
```
DefaultClassPathResolver (main)
  ├─ ShellClassPathResolver (custom scripts)
  ├─ MavenClassPathResolver (pom.xml)
  └─ GradleClassPathResolver (build.gradle/build.gradle.kts)
      └─ WithStdlibResolver (Kotlin stdlib)
          └─ BackupClassPathResolver (fallback)
└─ CachedClassPathResolver (optional, with in-memory cache)
```

---

## LSP Features Implemented

| Feature | Status | Notes |
|---------|--------|-------|
| Completion | ✅ | With auto-import support |
| Hover | ✅ | Type info + KDoc |
| Go-to-Definition | ✅ | Supports JAR decompilation |
| Find References | ✅ | |
| Document/Workspace Symbols | ✅ | |
| Signature Help | ✅ | |
| Rename | ✅ | |
| Code Actions | ✅ | Limited quick fixes (see below) |
| Formatting | ✅ | ktfmt integration |
| Semantic Tokens | ✅ | |
| Document Highlight | ✅ | |
| Inlay Hints | ✅ | Type/parameter/chained hints |
| Diagnostics | ✅ | With debounce |
| Code Lens | ❌ | Not implemented |
| On-Type Formatting | ❌ | Not implemented |

### Custom Protocol Extensions (`kotlin/*` namespace)

| Method | Description |
|--------|-------------|
| `kotlin/jarClassContents` | Get decompiled source from JAR classes |
| `kotlin/buildOutputLocation` | Get build output directory |
| `kotlin/mainClass` | Detect main function for running |
| `kotlin/overrideMember` | Get overridable members |

### Quick Fixes Implemented

Only 2 quick fixes are currently implemented:

1. **ImplementAbstractMembersQuickFix** - Generate implementation stubs for abstract members
2. **AddMissingImportsQuickFix** - Add imports for unresolved symbols

Other quick fixes (e.g., auto-correct typos, add missing returns) are not yet implemented.

---

## Build System

**Requirements:** Java 11+ (toolchain enforced via `gradle.properties`)

### Build Commands

| Command | Description |
|---------|-------------|
| `./gradlew :server:installDist` | Build binary distribution (recommended) |
| `./gradlew :server:distZip` | Create release ZIP |
| `./gradlew :server:test` | Run tests |
| `./gradlew :server:run` | Run standalone |
| `./gradlew :server:debugRun` | Debug mode (port 8000) |

### JVM Configuration

```kotlin
// server/build.gradle.kts
applicationDefaultJvmArgs = listOf(
    "-DkotlinLanguageServer.version=$version",
    "-Xms256m",                    // Initial heap: 256MB
    "-Xmx3072m",                   // Max heap: 3GB
    "-XX:+UseG1GC",                // G1 garbage collector
    "-XX:+UseStringDeduplication"  // Memory optimization
)
```

### Startup Modes

- **Stdio** (default) - Standard input/output
- **TCP Server** - `--tcpServerPort` for Docker usage
- **TCP Client** - `--tcpClientHost` `--tcpClientPort` for connecting to client

---

## Performance Optimizations

### JVM Tuning

| Setting | Value | Notes |
|---------|-------|-------|
| Initial heap | 256MB | `-Xms256m` |
| Max heap | 3GB | `-Xmx3072m` (required for large projects) |
| GC | G1GC | `-XX:+UseG1GC` |
| String deduplication | Enabled | `-XX:+UseStringDeduplication` |

### Compilation Strategy

- **Incremental compilation** - Only recompile changed files
- **Expression-level compilation** - Recompile only changed expressions
- **Cached class paths** - Avoid redundant classpath resolution
- **Debounce** - Delay diagnostics during rapid typing (default 250ms)

---

## Known Issues and TODOs

### Major TODOs in Codebase

| Location | Issue |
|----------|-------|
| `CompiledFile.kt:67` | JDK symbol resolution not working |
| `Compiler.kt:154-157` | KotlinScriptDefinition is deprecated |
| `CompilerClassPath.kt:54` | Parallel classpath/build script resolution |
| `FindReferences.kt:118,182` | Limit search using imports, improve selectivity |
| `SemanticTokens.kt:114` | Range cutoff optimization |

### Known Limitations

1. **Memory usage** - In-memory symbol database can consume significant memory
2. **JDK source** - JDK source code only available for JDK 9+
3. **Script support** - KotlinScriptDefinition is deprecated in Kotlin compiler

---

## Configuration Options

### Dynamic Configuration (`didChangeConfiguration`)

```json
{
  "kotlin": {
    "diagnostics": {
      "enabled": true,
      "level": "warning",
      "debounceTime": 250
    },
    "completion": {
      "snippets": { "enabled": true }
    },
    "formatting": {
      "formatter": "ktfmt",
      "ktfmt": {
        "style": "google",
        "indent": 4,
        "maxWidth": 100,
        "continuationIndent": 8,
        "removeUnusedImports": true
      }
    },
    "inlayHints": {
      "typeHints": false,
      "parameterHints": false,
      "chainedHints": false
    },
    "compiler": {
      "jvm": { "target": "default" }
    },
    "indexing": { "enabled": true },
    "scripts": {
      "enabled": true,
      "buildScriptsEnabled": true
    },
    "externalSources": {
      "useKlsScheme": false,
      "autoConvertToKotlin": false
    }
  }
}
```

> **Note:** `compiler.jvm.target` defaults to `"default"`, which follows the build toolchain (Java 11).

---

## Development Guide

### Key Classes to Understand

1. **`KotlinLanguageServer`** - Main server entry, handles LSP lifecycle
2. **`SourcePath`** - File cache management, incremental compilation
3. **`CompiledFile`** - Represents compiled file with binding context
4. **`Compiler`** - Kotlin compiler API wrapper
5. **`SymbolIndex`** - Global symbol indexing with Exposed ORM

### Adding a New LSP Feature

1. Create implementation in appropriate subdirectory under `server/src/main/kotlin/org/javacs/kt/`
2. Register in `KotlinTextDocumentService` or `KotlinWorkspaceService`
3. Advertise capability in `KotlinLanguageServer.initialize()`
4. Add tests in `server/src/test/kotlin/`

### Debugging

```bash
./gradlew :server:debugRun
# Attach debugger to port 8000
```

### Memory Troubleshooting

If experiencing OOM errors, increase heap size:
```bash
JAVA_OPTS="-Xmx2g" kotlin-language-server
```

---

## Troubleshooting

### Cache Corruption

If you see errors like `ClassPathCacheEntry.id is not in record set` or unexpected LSP behavior:

```bash
# Delete the .kls cache directory in your project
rm -rf <project-root>/.kls/

# Restart nvim/editor
```

### Clean Rebuild

If code changes don't seem to take effect:

```bash
./gradlew clean :server:installDist
```

---

## Editor Integration

Primary target: **Neovim** (via `nvim-lspconfig`)

Other supported editors: VSCode (vscode-kotlin), Emacs (lsp-mode), Helix

---

## Version Info

- **Version:** 1.3.14
- **Kotlin:** 2.1.0
- **Java:** 11 (required)
- **Status:** Deprecated by upstream, actively maintained in this fork
