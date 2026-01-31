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
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
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

        if (isExternalSource(rawClassURI, cp)) {
            findSourceInWorkspace(target, sp)?.let { return it }
            findSourceInModules(target, cp.moduleRegistry, cp.compiler)?.let { return it }

            parseURI(rawClassURI).toKlsURI()?.let { klsURI ->
                return resolveFromDecompiledSource(klsURI, target, classContentProvider, tempDir, config)
            }

            resolveFromClasspath(target, classContentProvider, tempDir, config, cp)?.let { return it }
        }

        return destination
    }

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

    sp.index.findSourceLocation(fqName)?.let { return it }

    val descriptor = resolveImportedDescriptor(fqName, file) ?: return null

    findSourceInWorkspace(descriptor, sp)?.let { return it }
    findSourceInModules(descriptor, cp.moduleRegistry, cp.compiler)?.let { return it }

    return resolveFromClasspath(descriptor, classContentProvider, tempDir, config, cp)
}

private fun resolveImportedDescriptor(fqName: FqName, file: CompiledFile): DeclarationDescriptor? {
    val module = file.module
    val parentFqName = fqName.parent()
    val shortName = fqName.shortName()

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
    val classFilePath = buildClassFilePath(target) ?: return null

    for (entry in cp.classPath) {
        val jarPath = entry.compiledJar
        if (!jarPath.toFile().exists()) continue

        try {
            val klsURI = KlsURI.fromJarAndClass(jarPath, classFilePath) ?: continue
            return resolveFromDecompiledSource(klsURI, target, classContentProvider, tempDir, config)
        } catch (e: Exception) {
            LOG.debug("Failed to resolve from {}: {}", jarPath, e.message)
        }
    }

    return null
}

private fun buildClassFilePath(descriptor: DeclarationDescriptor): String? {
    if (descriptor is CallableMemberDescriptor) {
        val originalDescriptor = findOriginalDeclaration(descriptor)

        if (originalDescriptor is DeserializedCallableMemberDescriptor) {
            val source = originalDescriptor.containerSource
            if (source is KotlinJvmBinarySourceElement) {
                val classId = source.binaryClass.classId
                return classId.packageFqName.asString().replace('.', '/') +
                    "/" + classId.relativeClassName.asString().replace('.', '$') + ".class"
            }
        }

        val containingClass = findContainingClass(originalDescriptor)
        if (containingClass != null) {
            return buildClassFilePathForClass(containingClass)
        }
    }

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

private fun findOriginalDeclaration(descriptor: CallableMemberDescriptor): CallableMemberDescriptor {
    var current = descriptor
    while (current.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) {
        val overridden = current.overriddenDescriptors.firstOrNull() ?: break
        current = overridden
    }
    return current
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

private fun isExternalSource(uri: String, cp: CompilerClassPath): Boolean {
    if (uri.contains(".jar!") || uri.contains(".zip!")) {
        return true
    }

    val path = try {
        Paths.get(parseURI(uri)).normalize().toAbsolutePath()
    } catch (e: Exception) {
        LOG.debug("Failed to parse URI {}, treating as external source: {}", uri, e.message)
        return true
    }

    cp.javaHome?.let { javaHome ->
        val javaHomePath = Paths.get(javaHome).normalize().toAbsolutePath()
        if (path.startsWith(javaHomePath)) {
            return true
        }
    }

    val gradleCacheDirs = listOfNotNull(
        System.getenv("GRADLE_USER_HOME")?.let { Paths.get(it, "caches") },
        Paths.get(System.getProperty("user.home"), ".gradle", "caches")
    ).map { it.normalize().toAbsolutePath() }

    if (gradleCacheDirs.any { cacheDir -> path.startsWith(cacheDir) }) {
        return true
    }

    val mavenRepoDirs = listOfNotNull(
        System.getenv("M2_HOME")?.let { Paths.get(it, "repository") },
        Paths.get(System.getProperty("user.home"), ".m2", "repository")
    ).map { it.normalize().toAbsolutePath() }

    if (mavenRepoDirs.any { repoDir -> path.startsWith(repoDir) }) {
        return true
    }

    val workspaceRoots = cp.workspaceRoots
    if (workspaceRoots.isNotEmpty()) {
        val isInsideWorkspace = workspaceRoots.any { root ->
            path.startsWith(root.normalize().toAbsolutePath())
        }
        if (!isInsideWorkspace) {
            LOG.debug("Path {} is outside workspace roots, treating as external source", path)
            return true
        }
    } else {
        LOG.debug("No workspace roots available, cannot determine if {} is external", path)
    }

    return false
}

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
    }?.sortedBy { it.name } ?: return null

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

        if (declaration is KtClassOrObject) {
            val nested = findDeclarationByPath(declaration.declarations, remainingPath)
            if (nested != null) return nested
        }
    }
    return null
}
