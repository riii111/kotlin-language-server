# LSP Features

## Implemented Features

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

## Custom Protocol Extensions (`kotlin/*` namespace)

| Method | Description |
|--------|-------------|
| `kotlin/jarClassContents` | Get decompiled source from JAR classes |
| `kotlin/buildOutputLocation` | Get build output directory |
| `kotlin/mainClass` | Detect main function for running |
| `kotlin/overrideMember` | Get overridable members |

## Quick Fixes Implemented

Only 2 quick fixes are currently implemented:

1. **ImplementAbstractMembersQuickFix** - Generate implementation stubs for abstract members
2. **AddMissingImportsQuickFix** - Add imports for unresolved symbols

Other quick fixes (e.g., auto-correct typos, add missing returns) are not yet implemented.
