package org.javacs.kt.definition

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import java.nio.file.Path
import org.javacs.kt.CompiledFile
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.LOG
import org.javacs.kt.SourcePath
import org.javacs.kt.ExternalSourcesConfiguration
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.externalsources.toKlsURI
import org.javacs.kt.externalsources.KlsURI
import org.javacs.kt.position.location
import org.javacs.kt.position.isZero
import org.javacs.kt.position.position
import org.javacs.kt.util.partitionAroundLast
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.parseURI
import org.javacs.kt.util.findParent
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.io.File
import java.nio.file.Paths

private val cachedTempFiles = mutableMapOf<KlsURI, Path>()
private val definitionPattern = Regex("(?:class|interface|object|fun|val|var)\\s+(\\w+)")

fun goToDefinition(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider,
    tempDir: TemporaryDirectory,
    config: ExternalSourcesConfiguration,
    cp: CompilerClassPath,
    sp: SourcePath
): Location? {
    // Handle import statements - referenceExpressionAtPoint returns package, not imported symbol
    file.elementAtPoint(cursor)?.findParent<KtImportDirective>()?.let { importDirective ->
        return resolveImportDefinition(importDirective, file, classContentProvider, tempDir, config, cp, sp)
    }

    val (_, target) = file.referenceExpressionAtPoint(cursor) ?: return null

    LOG.info("Found declaration descriptor {}", target)
    var destination = location(target)
    val psi = target.findPsi()

    if (psi is KtNamedDeclaration) {
        destination = psi.nameIdentifier?.let(::location) ?: destination
    }

    if (destination != null && !destination.range.isZero) {
        val rawClassURI = destination.uri

        if (isInsideArchive(rawClassURI, cp)) {
            findSourceInWorkspace(target, sp)?.let { return it }

            parseURI(rawClassURI).toKlsURI()?.let { klsURI ->
                return resolveFromDecompiledSource(klsURI, target, classContentProvider, tempDir, config)
            }
        }

        return destination
    }

    // DeserializedDescriptor (JAR-derived) has no location or zero range - resolve via classpath lookup
    findSourceInWorkspace(target, sp)?.let { return it }
    return resolveFromClasspath(target, classContentProvider, tempDir, config, cp)
}

private fun resolveImportDefinition(
    importDirective: KtImportDirective,
    file: CompiledFile,
    classContentProvider: ClassContentProvider,
    tempDir: TemporaryDirectory,
    config: ExternalSourcesConfiguration,
    cp: CompilerClassPath,
    sp: SourcePath
): Location? {
    val fqName = importDirective.importedFqName ?: return null
    LOG.info("Resolving import: {}", fqName)

    // First try workspace source via symbol index
    sp.index.findSourceLocation(fqName)?.let { return it }

    // Resolve the FQN to a descriptor via module's scope
    val descriptor = resolveImportedDescriptor(fqName, file) ?: return null

    // Try to find source in workspace
    findSourceInWorkspace(descriptor, sp)?.let { return it }

    // Fall back to JAR decompilation
    return resolveFromClasspath(descriptor, classContentProvider, tempDir, config, cp)
}

private fun resolveImportedDescriptor(fqName: FqName, file: CompiledFile): DeclarationDescriptor? {
    val module = file.module
    val parentFqName = fqName.parent()
    val shortName = fqName.shortName()

    // Try to find in package scope
    val packageView = module.getPackage(parentFqName)
    val candidates = packageView.memberScope.getContributedDescriptors { it == shortName }
        .filter { it.name == shortName }

    return candidates.firstOrNull()
}

private fun resolveFromDecompiledSource(
    klsURI: KlsURI,
    target: DeclarationDescriptor,
    classContentProvider: ClassContentProvider,
    tempDir: TemporaryDirectory,
    config: ExternalSourcesConfiguration
): Location {
    val (klsSourceURI, content) = classContentProvider.contentOf(klsURI)

    val uri = if (config.useKlsScheme) {
        klsSourceURI.toString()
    } else {
        val tmpFile = cachedTempFiles[klsSourceURI] ?: run {
            val name = klsSourceURI.fileName.partitionAroundLast(".").first
            val extensionWithoutDot = klsSourceURI.fileExtension
            val extension = if (extensionWithoutDot != null) ".$extensionWithoutDot" else ""
            tempDir.createTempFile(name, extension)
                .also {
                    it.toFile().writeText(content)
                    cachedTempFiles[klsSourceURI] = it
                }
        }
        tmpFile.toUri().toString()
    }

    val range = findDefinitionRange(target, content)
    return Location(uri, range)
}

private fun resolveFromClasspath(
    target: DeclarationDescriptor,
    classContentProvider: ClassContentProvider,
    tempDir: TemporaryDirectory,
    config: ExternalSourcesConfiguration,
    cp: CompilerClassPath
): Location? {
    // Get the containing class or top-level file class for the descriptor
    val classDescriptor = findContainingClassDescriptor(target)
    if (classDescriptor == null) {
        LOG.debug("Could not find containing class for {}", target.fqNameSafe)
        return null
    }

    val fqName = classDescriptor.fqNameSafe
    val classFilePath = fqName.asString().replace('.', '/') + ".class"

    LOG.debug("Looking for class file: {}", classFilePath)

    // Search in classpath for the class file
    for (entry in cp.classPath) {
        val jarPath = entry.compiledJar
        if (!jarPath.toFile().exists()) continue

        try {
            val klsURI = KlsURI.fromJarAndClass(jarPath, classFilePath) ?: continue
            LOG.debug("Found class in JAR: {}", klsURI)
            return resolveFromDecompiledSource(klsURI, target, classContentProvider, tempDir, config)
        } catch (e: Exception) {
            LOG.debug("Failed to resolve from {}: {}", jarPath, e.message)
        }
    }

    LOG.debug("Could not find class file {} in classpath", classFilePath)
    return null
}

private fun findContainingClassDescriptor(descriptor: DeclarationDescriptor): DeclarationDescriptor? {
    var current: DeclarationDescriptor? = descriptor
    while (current != null) {
        if (current is org.jetbrains.kotlin.descriptors.ClassDescriptor) {
            return current
        }
        // For top-level functions/properties, use the package's file facade
        if (current is org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor) {
            // Top-level declarations are in a file facade class
            // The class name is derived from the file name, e.g., FooKt for Foo.kt
            // We can't determine the exact file name from the descriptor alone,
            // but the descriptor's source might contain this info
            return descriptor // Return the original descriptor; we'll handle this case specially
        }
        current = current.containingDeclaration
    }
    return descriptor
}

private fun findDefinitionRange(target: DeclarationDescriptor, content: String): Range {
    val name = when (target) {
        is ConstructorDescriptor -> target.constructedClass.name.toString()
        else -> target.name.toString()
    }
    return definitionPattern.findAll(content)
        .map { it.groups[1]!! }
        .find { it.value == name }
        ?.let { Range(position(content, it.range.first), position(content, it.range.last)) }
        ?: Range(org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.Position(0, 0))
}

private fun findSourceInWorkspace(
    target: DeclarationDescriptor,
    sp: SourcePath
): Location? = sp.index.findSourceLocation(target.fqNameSafe)

private fun isInsideArchive(uri: String, cp: CompilerClassPath) =
    uri.contains(".jar!") || uri.contains(".zip!") || cp.javaHome?.let {
        Paths.get(parseURI(uri)).toString().startsWith(File(it).path)
    } ?: false
