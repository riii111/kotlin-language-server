package org.javacs.kt.codegen

import org.javacs.kt.LOG
import org.javacs.kt.compiler.Compiler
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext

/**
 * Service responsible for generating code from compiled Kotlin files.
 * Extracted from SourcePath to separate code generation concerns.
 */
class CodeGenerationService(
    private val compilerProvider: (String?) -> Compiler
) {
    /**
     * Generate code for the given compiled files.
     * @param module The module descriptor
     * @param context The binding context from compilation
     * @param files The compiled KtFiles to generate code for
     * @param moduleId The module ID for the compiler lookup
     */
    fun generateCode(
        module: ModuleDescriptor,
        context: BindingContext,
        files: List<KtFile>,
        moduleId: String?
    ) {
        try {
            val moduleCompiler = compilerProvider(moduleId)
            moduleCompiler.generateCode(module, context, files)
        } catch (ex: Exception) {
            LOG.printStackTrace(ex)
        }
    }

    /**
     * Remove previously generated code for the given files.
     * @param files The KtFiles whose generated code should be removed
     * @param moduleId The module ID for the compiler lookup
     */
    fun removeGeneratedCode(files: List<KtFile>, moduleId: String?) {
        try {
            val moduleCompiler = compilerProvider(moduleId)
            moduleCompiler.removeGeneratedCode(files)
        } catch (ex: Exception) {
            LOG.printStackTrace(ex)
        }
    }
}
