# Performance

## JVM Configuration

```kotlin
// server/build.gradle.kts
applicationDefaultJvmArgs = listOf(
    "-DkotlinLanguageServer.version=$version",
    "-Xms1g",                      // Initial heap: 1GB
    "-Xmx16g",                     // Max heap: 16GB (for large monorepo projects)
    "-XX:+UseG1GC",                // G1 garbage collector
    "-XX:+UseStringDeduplication"  // Memory optimization
)
```

> **Warning:** Do NOT set `JAVA_OPTS` or `KOTLIN_LANGUAGE_SERVER_OPTS` environment variables with `-Xmx` flags, as they will override the default settings above (the last `-Xmx` wins).

## JVM Tuning

| Setting | Value | Notes |
|---------|-------|-------|
| Initial heap | 1GB | `-Xms1g` |
| Max heap | 16GB | `-Xmx16g` (for large monorepo projects) |
| GC | G1GC | `-XX:+UseG1GC` |
| String deduplication | Enabled | `-XX:+UseStringDeduplication` |

## Compilation Strategy

- **Incremental compilation** - Only recompile changed files
- **Expression-level compilation** - Recompile only changed expressions
- **Cached class paths** - Avoid redundant classpath resolution
- **Debounce** - Delay diagnostics during rapid typing (default 250ms)

## Large Monorepo Projects (5,000+ files)

For very large projects, **disable indexing** to prevent OutOfMemoryError:

```json
{
  "kotlin": {
    "indexing": { "enabled": false }
  }
}
```

**Impact of disabling indexing:**

| Feature | With Indexing | Without Indexing |
|---------|---------------|------------------|
| Go to Definition (gd) | ✅ | ✅ |
| Find References (gr) | ✅ Project-wide | ⚠️ Open files only |
| Workspace Symbol Search | ✅ | ❌ |
| Diagnostics | ✅ | ✅ |
| Completion | ✅ | ✅ |
| Hover | ✅ | ✅ |

**Recommendation:** For projects with 5,000+ files or 20+ submodules, disable indexing. Use file search (Telescope/fzf) and ripgrep as alternatives to workspace symbol search.
