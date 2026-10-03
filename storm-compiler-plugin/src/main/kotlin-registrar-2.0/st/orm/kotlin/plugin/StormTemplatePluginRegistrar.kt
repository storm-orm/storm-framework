package st.orm.kotlin.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Entry point for the Storm Template compiler plugin.
 *
 * Registers an IR extension that auto-wraps string interpolations inside
 * [TemplateBuilder][st.orm.template.TemplateBuilder] lambdas with [t()][st.orm.template.TemplateContext.t] calls.
 *
 * Without the plugin, users write:
 * ```kotlin
 * orm.query { "SELECT ${t(User::class)} FROM ${t(User::class)} WHERE id = ${t(id)}" }
 * ```
 *
 * With the plugin, the explicit `t()` wrapping is no longer needed:
 * ```kotlin
 * orm.query { "SELECT ${User::class} FROM ${User::class} WHERE id = $id" }
 * ```
 *
 * The plugin inserts the `t()` calls at compile time, producing identical runtime behavior.
 */
@OptIn(ExperimentalCompilerApi::class)
class StormTemplatePluginRegistrar : CompilerPluginRegistrar() {

    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // This registrar serves the 2.0 to 2.2 compilers. Kotlin 2.0 has no CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
        // so the key it has stays in use; later compilers deprecate it in favor of the one 2.0 lacks.
        @Suppress("DEPRECATION")
        val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        IrGenerationExtension.registerExtension(StormTemplateIrGenerationExtension(messageCollector))
    }
}
