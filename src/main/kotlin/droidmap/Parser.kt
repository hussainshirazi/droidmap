package droidmap

import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * The Kotlin compiler's parsers for Kotlin and Java (the PSI that detekt and ktlint use), set up once
 * per process. Parsing builds independent trees, so files are parsed on several threads at once.
 */
object Parser {
    @Suppress("OPT_IN_USAGE_ERROR")
    @OptIn(CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
    private val project by lazy {
        setIdeaIoUseFallback()
        val config = CompilerConfiguration()
        config.extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
        KotlinCoreEnvironment.createForProduction(Disposer.newDisposable("droidmap"), config, EnvironmentConfigFiles.JVM_CONFIG_FILES).project
    }
    private val kotlin by lazy { KtPsiFactory(project, markGenerated = false) }
    private val files by lazy { PsiFileFactory.getInstance(project) }

    fun kotlin(name: String, text: String): KtFile = kotlin.createFile(name, text.replace("\r\n", "\n"))

    fun java(name: String, text: String): PsiJavaFile =
        files.createFileFromText(name, JavaLanguage.INSTANCE, text.replace("\r\n", "\n")) as PsiJavaFile
}
