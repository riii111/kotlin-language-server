# Architecture

## Project Structure

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

## Key Classes

1. **`KotlinLanguageServer`** - Main server entry, handles LSP lifecycle
2. **`SourcePath`** - File cache management, incremental compilation
3. **`CompiledFile`** - Represents compiled file with binding context
4. **`Compiler`** - Kotlin compiler API wrapper
5. **`SymbolIndex`** - Global symbol indexing with Exposed ORM
6. **`CompilerClassPath`** - Kotlin compiler classpath management with background resolution

## Background Classpath Resolution

Classpath resolution (Gradle/Maven CLI execution) runs in the background to provide instant LSP startup:

```
┌─────────┐    ┌───────────┐    ┌───────┐
│ PENDING │───▶│ RESOLVING │───▶│ READY │
└─────────┘    └───────────┘    └───────┘
                    │                ▲
                    │ (error)        │ (retry)
                    ▼                │
               ┌────────┐────────────┘
               │ FAILED │
               └────────┘
```

**Degraded Mode (during PENDING/RESOLVING):**
- Syntax highlighting: Works
- Document symbols: Works
- Go to Definition (local): Works
- Go to Definition (external): Limited
- Completion (local): Works
- Completion (external): Limited
- Diagnostics: Disabled (to prevent false positives)

## Adding a New LSP Feature

1. Create implementation in appropriate subdirectory under `server/src/main/kotlin/org/javacs/kt/`
2. Register in `KotlinTextDocumentService` or `KotlinWorkspaceService`
3. Advertise capability in `KotlinLanguageServer.initialize()`
4. Add tests in `server/src/test/kotlin/`
