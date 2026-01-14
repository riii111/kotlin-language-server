package org.javacs.kt.imports

import org.javacs.kt.LOG
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.externalsources.KlsURI
import org.javacs.kt.util.AsyncExecutor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.name.FqName
import java.net.URI
import java.util.zip.ZipFile
import java.util.concurrent.ConcurrentHashMap

class ImportPreloader(
    private val classContentProvider: ClassContentProvider,
    private val cp: CompilerClassPath
) {
    private val async = AsyncExecutor()
    private val preloadedClasses = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private val SKIP_PREFIXES = listOf("kotlin.", "java.", "javax.")
    }

    fun preloadImports(file: KtFile) {
        val imports = file.importDirectives.mapNotNull { it.importedFqName }
        if (imports.isEmpty()) return

        async.execute {
            for (fqName in imports) {
                preloadClass(fqName)
            }
        }
    }

    private fun preloadClass(fqName: FqName) {
        val fqNameStr = fqName.asString()

        if (SKIP_PREFIXES.any { fqNameStr.startsWith(it) }) return
        if (preloadedClasses.contains(fqNameStr)) return

        try {
            val klsUri = findClassInClasspath(fqName) ?: return
            LOG.debug("Preloading import: {}", fqNameStr)
            classContentProvider.contentOf(klsUri)
            preloadedClasses.add(fqNameStr)
        } catch (e: Exception) {
            LOG.debug("Failed to preload {}: {}", fqNameStr, e.message)
        }
    }

    private fun findClassInClasspath(fqName: FqName): KlsURI? {
        val classPaths = generateClassPaths(fqName.asString())

        for (entry in cp.classPath) {
            val jarPath = entry.compiledJar
            if (!jarPath.toFile().exists()) continue

            try {
                ZipFile(jarPath.toFile()).use { zip ->
                    for (classPath in classPaths) {
                        val zipEntry = zip.getEntry(classPath)
                        if (zipEntry != null) {
                            val uri = URI("kls:${jarPath.toUri()}!/$classPath")
                            return KlsURI(uri)
                        }
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        return null
    }

    // Inner classes use $ separator in .class files (e.g., Outer$Inner.class)
    // but FQN uses dots, so we try multiple patterns
    private fun generateClassPaths(fqName: String): List<String> {
        val parts = fqName.split('.')
        val result = mutableListOf<String>()

        result.add(fqName.replace('.', '/') + ".class")

        for (i in parts.indices) {
            if (i > 0 && parts[i].firstOrNull()?.isUpperCase() == true) {
                val packagePart = parts.subList(0, i).joinToString("/")
                val classPart = parts.subList(i, parts.size).joinToString("$")
                val path = if (packagePart.isEmpty()) "$classPart.class" else "$packagePart/$classPart.class"
                if (path !in result) {
                    result.add(path)
                }
            }
        }

        return result
    }

    fun shutdown() {
        async.shutdown(false)
    }
}
