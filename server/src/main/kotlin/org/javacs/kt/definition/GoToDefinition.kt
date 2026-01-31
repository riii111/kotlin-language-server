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
import org.javacs.kt.classpath.ModuleRegistry
import org.javacs.kt.compiler.Compiler
import org.javacs.kt.util.partitionAroundLast
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.parseURI
import org.javacs.kt.util.findParent
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedCallableMemberDescriptor
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
            findSourceInModules(target, cp.moduleRegistry, cp.compiler)?.let { return it }

            parseURI(rawClassURI).toKlsURI()?.let { klsURI ->
                return resolveFromDecompiledSource(klsURI, target, classContentProvider, tempDir, config)
            }
        }

        return destination
    }

    // DeserializedDescriptor (JAR-derived) has no location or zero range - resolve via classpath lookup
    findSourceInWorkspace(target, sp)?.let { return it }
    findSourceInModules(target, cp.moduleRegistry, cp.compiler)?.let { return it }
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

    // Try to find source in workspace or other modules
    findSourceInWorkspace(descriptor, sp)?.let { return it }
    findSourceInModules(descriptor, cp.moduleRegistry, cp.compiler)?.let { return it }

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
    val classFilePath = buildClassFilePath(target)
    if (classFilePath == null) {
        LOG.debug("Could not determine class file path for {}", target.fqNameSafe)
        return null
    }

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

private fun buildClassFilePath(descriptor: DeclarationDescriptor): String? {
    if (descriptor is DeserializedCallableMemberDescriptor) {
        val source = descriptor.containerSource
        if (source is KotlinJvmBinarySourceElement) {
            val classId = source.binaryClass.classId
            return classId.packageFqName.asString().replace('.', '/') +
                "/" + classId.relativeClassName.asString().replace('.', '$') + ".class"
        }
    }

    val containingClass = findContainingClass(descriptor)
    if (containingClass != null) {
        return buildClassFilePathForClass(containingClass)
    }

    return null
}

private fun findContainingClass(descriptor: DeclarationDescriptor): ClassDescriptor? {
    var current: DeclarationDescriptor? = descriptor
    while (current != null) {
        if (current is ClassDescriptor) return current
        if (current is PackageFragmentDescriptor) return null
        current = current.containingDeclaration
    }
    return null
}

private fun buildClassFilePathForClass(classDescriptor: ClassDescriptor): String {
    val classNames = mutableListOf<String>()
    var current: DeclarationDescriptor? = classDescriptor

    while (current is ClassDescriptor) {
        classNames.add(current.name.asString())
        current = current.containingDeclaration
    }

    var packagePath = ""
    var temp: DeclarationDescriptor? = classDescriptor.containingDeclaration
    while (temp != null && temp !is PackageFragmentDescriptor) {
        temp = temp.containingDeclaration
    }
    if (temp is PackageFragmentDescriptor) {
        packagePath = temp.fqName.asString().replace('.', '/')
    }

    val className = classNames.reversed().joinToString("$")
    return if (packagePath.isEmpty()) "$className.class" else "$packagePath/$className.class"
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

// SymbolIndex is per-module, so cross-module symbols aren't indexed.
// This searches all modules' source directories directly using FQN-based path resolution.
private fun findSourceInModules(
    target: DeclarationDescriptor,
    moduleRegistry: ModuleRegistry,
    compiler: Compiler
): Location? {
    if (moduleRegistry.isEmpty()) {
        return null
    }

    val fqName = target.fqNameSafe
    val packageFqName = getPackageFqName(target)
    val packagePath = packageFqName.asString().replace('.', File.separatorChar)
    val declarationPath = getDeclarationPath(fqName, packageFqName)

    LOG.debug("Searching for {} in modules (package: {}, path: {})", fqName, packageFqName, declarationPath)

    for (module in moduleRegistry.allModules()) {
        for (sourceDir in module.sourceDirs) {
            val packageDir = if (packagePath.isEmpty()) sourceDir else sourceDir.resolve(packagePath)
            if (!packageDir.toFile().exists() || !packageDir.toFile().isDirectory) {
                continue
            }

            val location = searchInDirectory(packageDir, packageFqName, declarationPath, compiler)
            if (location != null) {
                LOG.info("Found {} in module '{}' at {}", fqName, module.name, location.uri)
                return location
            }
        }
    }

    LOG.debug("Could not find {} in any module source directory", fqName)
    return null
}

private fun getPackageFqName(descriptor: DeclarationDescriptor): FqName {
    var current: DeclarationDescriptor? = descriptor
    while (current != null) {
        if (current is PackageFragmentDescriptor) {
            return current.fqName
        }
        current = current.containingDeclaration
    }
    return FqName.ROOT
}

private fun getDeclarationPath(fqName: FqName, packageFqName: FqName): List<String> {
    val fqNameStr = fqName.asString()
    val packageStr = packageFqName.asString()
    val relativePath = if (packageStr.isEmpty()) fqNameStr else fqNameStr.removePrefix("$packageStr.")
    return relativePath.split('.')
}

private fun searchInDirectory(
    packageDir: Path,
    packageFqName: FqName,
    declarationPath: List<String>,
    compiler: Compiler
): Location? {
    val ktFiles = packageDir.toFile().listFiles { file ->
        file.isFile && file.extension == "kt"
    } ?: return null

    for (file in ktFiles) {
        try {
            val location = searchInFile(file.toPath(), packageFqName, declarationPath, compiler)
            if (location != null) {
                return location
            }
        } catch (e: Exception) {
            LOG.debug("Error parsing {}: {}", file, e.message)
        }
    }
    return null
}

private fun searchInFile(
    filePath: Path,
    packageFqName: FqName,
    declarationPath: List<String>,
    compiler: Compiler
): Location? {
    val content = filePath.toFile().readText()
    val ktFile = compiler.createKtFile(content, filePath)

    if (ktFile.packageFqName != packageFqName) {
        return null
    }

    val declaration = findDeclarationByPath(ktFile.declarations, declarationPath)
    if (declaration != null) {
        val nameIdentifier = (declaration as? KtNamedDeclaration)?.nameIdentifier ?: declaration
        return location(nameIdentifier)
    }
    return null
}

// Traverse declarations following the path (e.g., ["MyClass", "InnerClass", "method"])
private fun findDeclarationByPath(
    declarations: List<KtDeclaration>,
    path: List<String>
): KtDeclaration? {
    if (path.isEmpty()) return null

    val targetName = path.first()
    val remainingPath = path.drop(1)

    for (declaration in declarations) {
        val name = (declaration as? KtNamedDeclaration)?.name ?: continue
        if (name != targetName) continue

        if (remainingPath.isEmpty()) {
            return declaration
        }

        // Continue traversing into nested declarations
        if (declaration is KtClassOrObject) {
            val nested = findDeclarationByPath(declaration.declarations, remainingPath)
            if (nested != null) return nested
        }
    }
    return null
}
