package org.javacs.kt.classpath

import java.nio.file.Path

data class ModuleInfo(
    val name: String,
    val rootPath: Path,
    val sourceDirs: Set<Path>,
    val classPath: Set<Path>
) {
    fun containsFile(filePath: Path): Boolean {
        val normalizedFilePath = filePath.toAbsolutePath().normalize()
        return sourceDirs.any { sourceDir ->
            val normalizedSourceDir = sourceDir.toAbsolutePath().normalize()
            normalizedFilePath.startsWith(normalizedSourceDir)
        }
    }
}
