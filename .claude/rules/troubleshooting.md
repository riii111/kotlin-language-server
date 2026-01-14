# Troubleshooting

## OutOfMemoryError on Large Projects

If you see `java.lang.OutOfMemoryError` during indexing:

1. **Disable indexing** (recommended for 5,000+ files):
   ```json
   { "kotlin": { "indexing": { "enabled": false } } }
   ```

2. **Or increase heap** (if indexing is required):
   - Edit `server/build.gradle.kts` and increase `-Xmx` value
   - Rebuild with `./gradlew :server:installDist`
   - Do NOT use `KOTLIN_LANGUAGE_SERVER_OPTS` env var (it overrides default settings)

## Cache Corruption

If you see errors like `ClassPathCacheEntry.id is not in record set` or unexpected LSP behavior:

```bash
# Delete the .kls cache directory in your project
rm -rf <project-root>/.kls/

# Also delete the LSP cache if using storagePath
rm -rf ~/.cache/kotlin-language-server/

# Restart nvim/editor
```

## Clean Rebuild

If code changes don't seem to take effect:

```bash
./gradlew clean :server:installDist
```

## Debugging

```bash
./gradlew :server:debugRun
# Attach debugger to port 8000
```

## Degraded Mode During Startup

When the LSP starts, classpath resolution runs in the background. During this time:

- **Diagnostics are disabled** to prevent false positive errors
- **External dependencies** are not available for go-to-definition, completion
- **Local symbols** and syntax features work normally

A progress notification "Resolving dependencies..." is shown during resolution. Once complete, diagnostics appear and full functionality is available.

## Known Limitations

1. **Memory usage** - SymbolIndex loads all symbols into memory, causing OOM on large projects (5,000+ files). Disable indexing for such projects.
2. **JDK source** - JDK source code only available for JDK 9+
3. **Script support** - KotlinScriptDefinition is deprecated in Kotlin compiler
4. **Exposed ORM Entity cache** - Entity API can cause "id is not in record set" errors. Use direct SQL (Table.insert/update/delete) instead of Entity.new/delete.

## Major TODOs in Codebase

| Location | Issue |
|----------|-------|
| `CompiledFile.kt:67` | JDK symbol resolution not working |
| `Compiler.kt:154-157` | KotlinScriptDefinition is deprecated |
| `CompilerClassPath.kt:90` | Parallel classpath/build script resolution (partially addressed by background resolution) |
| `FindReferences.kt:118,182` | Limit search using imports, improve selectivity |
| `SemanticTokens.kt:114` | Range cutoff optimization |
