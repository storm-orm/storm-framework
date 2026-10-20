@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package st.orm.kotlin.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irConcat
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName

/**
 * IR transformer that auto-wraps string interpolations in `t()` calls inside `TemplateBuilder` lambdas.
 *
 * The transformer detects lambda functions whose extension receiver type is `st.orm.template.TemplateContext`, then
 * finds `IrStringConcatenation` nodes (Kotlin string templates) inside those lambdas. For each non-constant
 * interpolated expression, it wraps the expression in a call to `TemplateContext.t(Any?): String`.
 *
 * Expressions already wrapped in `t()` or `insert()` are left unchanged, so explicit usage remains valid.
 *
 * The `+` operator on strings follows the same rules, so `"SELECT $col, " + "COUNT(*)"` yields the same template as
 * `"SELECT $col, COUNT(*)"`: literal operands are SQL text and every other operand is interpolated. An expression
 * inside an interpolation yields a value rather than SQL, so its own interpolations and concatenations are left to
 * Kotlin: `"... LIKE ${"%" + name + "%"}"` interpolates a single string.
 *
 * Constant interpolations like `${"value"}` are folded into the surrounding template text by the Kotlin compiler
 * before this transformer runs. The transformer parses the source text behind such folded constants to split them
 * back into SQL text and t()-wrapped values, and verifies the reassembled text against the constant's actual value.
 * A folded constant that cannot be split provably-correctly is reported as a compiler error, so an interpolation
 * can never silently remain SQL text.
 *
 * Example transformation:
 *
 * Source:
 * ```kotlin
 * orm.query { "SELECT ${User::class} FROM ${User::class} WHERE id = $id" }
 * ```
 *
 * Rewritten IR (equivalent to):
 * ```kotlin
 * orm.query { "SELECT ${t(User::class)} FROM ${t(User::class)} WHERE id = ${t(id)}" }
 * ```
 */
class StormTemplateIrTransformer(
    private val pluginContext: IrPluginContext,
    private val messageCollector: MessageCollector = MessageCollector.NONE,
) : IrElementTransformerVoid() {

    companion object {
        private val TEMPLATE_CONTEXT_FQN = FqName("st.orm.template.TemplateContext")
        private val STRING_FQN = FqName("kotlin.String")
    }

    /**
     * Tracks whether the expression being visited contributes SQL text. Text position covers the statements of a
     * TemplateBuilder lambda; the arguments of a string template (`${...}`) are value position, because they yield
     * bind values rather than SQL.
     */
    private var textPosition: Boolean = false

    /**
     * Tracks the `TemplateContext` receiver parameter when we're inside a TemplateBuilder lambda, so we can generate
     * `receiver.t(expr)` calls. Null when outside such a lambda.
     */
    private var templateContextReceiver: IrValueParameter? = null

    /** Cached symbol for `TemplateContext.t(Any?): String`. */
    private var tFunctionSymbol: IrSimpleFunction? = null

    /** Cached symbol for `TemplateContext.autoInterpolation()`. */
    private var autoInterpolationSymbol: IrSimpleFunction? = null

    /** The file being visited, for diagnostic locations. */
    private var currentFile: IrFile? = null

    /** Parser over the current file's source text, or null when the source cannot be read. */
    private var currentParser: TemplateSourceParser? = null

    /** Limits the unreadable-source warning to one per file. */
    private var unverifiableReported: Boolean = false

    override fun visitFile(declaration: IrFile): IrFile {
        currentFile = declaration
        currentParser = try {
            TemplateSourceParser(java.io.File(declaration.fileEntry.name).readText())
        } catch (_: Exception) {
            null
        }
        unverifiableReported = false
        val result = super.visitFile(declaration)
        currentFile = null
        currentParser = null
        return result
    }

    override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
        val function = expression.function
        // Check if this lambda has TemplateContext as its extension receiver type.
        val extensionReceiver = function.extensionReceiverParameter ?: return super.visitFunctionExpression(expression)
        val receiverFqName = extensionReceiver.type.classFqName ?: return super.visitFunctionExpression(expression)
        if (receiverFqName != TEMPLATE_CONTEXT_FQN) {
            return super.visitFunctionExpression(expression)
        }
        // The receiver's class declares the functions the rewrite calls.
        val templateContextClass = extensionReceiver.type.classOrNull?.owner ?: return super.visitFunctionExpression(expression)
        // We're inside a TemplateBuilder lambda. Set the receiver so nested visits can use it.
        val previousReceiver = templateContextReceiver
        val previousTextPosition = textPosition
        templateContextReceiver = extensionReceiver
        textPosition = true
        // Resolve function symbols if not already cached.
        if (tFunctionSymbol == null) {
            tFunctionSymbol = resolveTFunction(templateContextClass)
        }
        if (autoInterpolationSymbol == null) {
            autoInterpolationSymbol = resolveAutoInterpolationFunction(templateContextClass)
        }
        val result = super.visitFunctionExpression(expression)
        // Recover templates that folded into a single constant; they have no concatenation for the visitor above.
        tFunctionSymbol?.let { tFunction ->
            function.body?.transformChildrenVoid(ResultConstantRewriter(function, extensionReceiver, tFunction))
        }
        // Inject autoInterpolation() call at the start of the lambda body to signal that the plugin is active.
        val autoInterpolation = autoInterpolationSymbol
        if (autoInterpolation != null) {
            injectAutoInterpolationCall(function, extensionReceiver, autoInterpolation)
        }
        templateContextReceiver = previousReceiver
        textPosition = previousTextPosition
        return result
    }

    override fun visitStringConcatenation(expression: IrStringConcatenation): IrExpression {
        if (templateContextReceiver == null || tFunctionSymbol == null) {
            return super.visitStringConcatenation(expression)
        }
        if (!textPosition) {
            // Value position: the string is computed as an ordinary Kotlin expression and interpolated as a single
            // bind value, so its own interpolations are left alone. Nested TemplateBuilder lambdas are still visited.
            return super.visitStringConcatenation(expression)
        }
        return processConcatenation(expression, isOperatorConcatenation(expression))
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val tFunction = tFunctionSymbol
        if (templateContextReceiver == null || tFunction == null || !textPosition || !isStringPlus(expression)) {
            return super.visitCall(expression)
        }
        // `a + b` in text position means the same as "$a$b", so rewrite it into a concatenation and apply the
        // template rules to its operands. Nested `+` calls become concatenations in turn and are flattened below.
        val operands = listOfNotNull(expression.dispatchReceiver, expression.getValueArgument(0))
        if (operands.size != 2) {
            return super.visitCall(expression)
        }
        val builder = DeclarationIrBuilder(pluginContext, tFunction.symbol, expression.startOffset, expression.endOffset)
        val concatenation = builder.irConcat()
        concatenation.arguments.addAll(operands)
        return processConcatenation(concatenation, operatorConcatenation = true)
    }

    /**
     * Recovers templates that folded into a single string constant: a lambda whose interpolations are all constant
     * yields a plain constant result with no concatenation for the visitor to rewrite. The rewrite is limited to
     * the lambda's result positions, i.e. the returned expression and the expressions it derives from: branch
     * results and call receivers such as a `trimIndent()` chain. Constants elsewhere in the lambda, e.g. messages
     * inside nested lambdas, are not template text and keep their folded value.
     */
    private inner class ResultConstantRewriter(
        private val function: org.jetbrains.kotlin.ir.declarations.IrFunction,
        private val receiver: IrValueParameter,
        private val tFunction: IrSimpleFunction,
    ) : IrElementTransformerVoid() {

        override fun visitReturn(expression: IrReturn): IrExpression {
            val result = super.visitReturn(expression)
            if (result is IrReturn && result.returnTargetSymbol == function.symbol) {
                result.value = rewriteResultExpression(result.value)
            }
            return result
        }

        private fun rewriteResultExpression(expression: IrExpression): IrExpression {
            when (expression) {
                is IrConst<*> -> if (expression.value is String) {
                    return replaceFoldedConstant(expression, receiver, tFunction)
                }
                is IrWhen -> expression.branches.forEach { branch ->
                    branch.result = rewriteResultExpression(branch.result)
                }
                is IrBlock -> {
                    val statements = expression.statements
                    val last = statements.lastOrNull()
                    if (last is IrExpression) {
                        statements[statements.size - 1] = rewriteResultExpression(last)
                    }
                }
                is IrTypeOperatorCall -> expression.argument = rewriteResultExpression(expression.argument)
                is IrCall -> rewriteCallReceivers(expression)
                else -> {}
            }
            return expression
        }

        private fun rewriteCallReceivers(call: IrCall) {
            call.dispatchReceiver?.let { call.dispatchReceiver = rewriteResultExpression(it) }
            call.extensionReceiver?.let { call.extensionReceiver = rewriteResultExpression(it) }
        }
    }

    /** Splits a folded string constant into a concatenation of its template parts, or returns it unchanged. */
    private fun replaceFoldedConstant(
        irConst: IrConst<*>,
        receiver: IrValueParameter,
        tFunction: IrSimpleFunction,
    ): IrExpression {
        val parts = classifyStringConst(irConst, enclosingKind = null, ConstPosition.STANDALONE, receiver, tFunction)
        if (parts.size == 1 && parts[0] === irConst) {
            return irConst
        }
        val builder = DeclarationIrBuilder(pluginContext, tFunction.symbol, irConst.startOffset, irConst.endOffset)
        val concatenation = builder.irConcat()
        concatenation.arguments.addAll(parts)
        return concatenation
    }

    /**
     * Applies the template rules to the arguments of [expression]: literal text stays a fragment and every other
     * argument is wrapped in a `t()` call.
     *
     * When [operatorConcatenation] is true, the node represents a `+` chain rather than a single string literal. Its
     * arguments are operands that contribute SQL text, so they are visited in text position and nested concatenations
     * are spliced in rather than interpolated as a value. The arguments of a string literal are `${...}` expressions,
     * which are visited in value position.
     */
    private fun processConcatenation(
        expression: IrStringConcatenation,
        operatorConcatenation: Boolean,
    ): IrExpression {
        val receiver = templateContextReceiver ?: return expression
        val tFunction = tFunctionSymbol ?: return expression
        // The literal's own prefix determines how markers and escapes in its folded constants are interpreted.
        val enclosingKind = if (operatorConcatenation) null else currentParser?.literalKindAt(expression.startOffset)
        // Recursively transform each argument first, so that nested TemplateBuilder lambdas (e.g., inside subquery
        // calls) are processed before we wrap the argument in t(). Constants have no children to transform and are
        // classified below instead, keeping their handling out of visitConst's standalone path.
        val newArguments = expression.arguments.flatMap { argument ->
            val transformed = if (argument is IrConst<*>) argument else transformInPosition(argument, operatorConcatenation)
            when {
                transformed is IrConst<*> && transformed.value is String ->
                    classifyStringConst(transformed, enclosingKind, ConstPosition.of(operatorConcatenation), receiver, tFunction)
                transformed is IrStringConcatenation && operatorConcatenation -> transformed.arguments.toList()
                isAlreadyWrappedInT(transformed) -> listOf(transformed)
                else -> listOf(wrapInT(transformed, receiver, tFunction))
            }
        }
        checkQuotedInterpolations(newArguments)
        expression.arguments.clear()
        expression.arguments.addAll(newArguments)
        return expression
    }

    /**
     * Reports a value interpolated inside a SQL string literal.
     *
     * Such a value renders as the literal text `'?'`, which the database reads as a string holding a question mark
     * rather than a placeholder. The value is still bound but has nowhere to bind to, so every parameter after it
     * takes the position before its own: where the leftover count does not balance the driver rejects the bind,
     * and where it does balance the statement runs against the wrong arguments and returns results that look
     * ordinary. Quoting is never what the author wants here, since the value is bound rather than pasted in.
     */
    private fun checkQuotedInterpolations(arguments: List<IrExpression>) {
        var inLiteral = false
        for (argument in arguments) {
            if (argument is IrConst<*> && argument.value is String) {
                // A doubled quote escapes itself in SQL, which toggling twice already handles.
                for (character in argument.value as String) {
                    if (character == '\'') {
                        inLiteral = !inLiteral
                    }
                }
            } else if (inLiteral) {
                report(
                    CompilerMessageSeverity.ERROR,
                    "This value is interpolated inside a SQL string literal, where it renders as '?': the database " +
                        "reads a string holding a question mark rather than a placeholder, so the value never " +
                        "reaches the statement while it stays bound, and the parameters after it bind one position " +
                        "early. Interpolate it without the quotes around it.",
                    argument,
                )
                // One report per template: the positions after this one are shifted by it, not faults of their own.
                return
            }
        }
    }

    /** Transforms [expression] with [textPosition] set for the duration of the visit. */
    private fun transformInPosition(expression: IrExpression, textPosition: Boolean): IrExpression {
        val previousTextPosition = this.textPosition
        this.textPosition = textPosition
        val result = expression.transform(this, null)
        this.textPosition = previousTextPosition
        return result
    }

    /**
     * Checks whether an [IrStringConcatenation] represents a `+` chain rather than a single string literal.
     *
     * The compiler folds `"a${b}" + "c"` into one concatenation node whose arguments are the operands, which is
     * indistinguishable in shape from the arguments of a string literal. The source range tells them apart: the
     * arguments of a string literal all start after its opening quote, while the first operand of a `+` chain starts
     * where the chain itself starts.
     */
    private fun isOperatorConcatenation(expression: IrStringConcatenation): Boolean {
        val first = expression.arguments.firstOrNull() ?: return false
        if (expression.startOffset < 0 || first.startOffset < 0) return false
        return first.startOffset <= expression.startOffset
    }

    /** Checks whether a call is `String.plus`, the desugared form of the `+` operator on strings. */
    private fun isStringPlus(expression: IrCall): Boolean {
        if (expression.symbol.owner.name.asString() != "plus") return false
        val receiverType = expression.dispatchReceiver?.type ?: return false
        return receiverType.classFqName == STRING_FQN
    }

    /**
     * Applies the template rules to a string constant in text position and returns the expressions that replace it.
     *
     * A constant whose source text spells out its value verbatim is literal template text and stays a fragment. Any
     * other constant is handed to [TemplateSourceParser]: when its source region parses as template syntax and the
     * reassembled text equals the constant's value, the folded `${"..."}` interpolations become t()-wrapped values
     * and the rest stays text. A region that contains interpolation syntax but cannot be split that way is reported
     * as a compiler error; regions without interpolation syntax (plain literals, `+` chain operands, escaped text)
     * are left unchanged.
     */
    private fun classifyStringConst(
        irConst: IrConst<*>,
        enclosingKind: LiteralKind?,
        position: ConstPosition,
        receiver: IrValueParameter,
        tFunction: IrSimpleFunction,
    ): List<IrExpression> {
        val value = irConst.value as String
        val parser = currentParser
        val start = irConst.startOffset
        val end = irConst.endOffset
        if (parser == null || start < 0 || end < start || !parser.inBounds(end)) {
            // Without source text the constant cannot be verified. A concatenation argument whose source span does
            // not match its value length either contains folded constants or escape sequences; neither can be told
            // apart nor checked, which is worth one warning per file. Standalone constants are mostly ordinary
            // literals whose span includes the quotes, so they stay silent.
            if (position == ConstPosition.TEMPLATE_ARGUMENT && parser == null && end - start != value.length) {
                reportUnverifiableTemplate(irConst)
            }
            return listOf(unverifiedConst(irConst, position, receiver, tFunction))
        }
        if (parser.matchesSource(start, end, value)) {
            // Literal template text.
            return listOf(irConst)
        }
        val parts = parser.parse(start, end, enclosingKind)
        if (parts != null && parts.joinToString("") { it.text } == value) {
            if (parts.none { it is TemplateValue }) {
                // Literal text whose source spelling differs from its value: escape sequences or quoted operands.
                return listOf(irConst)
            }
            return parts.map { part ->
                val partConst = createStringConst(part.startOffset, part.endOffset, irConst.type, part.text)
                if (part is TemplateValue) wrapInT(partConst, receiver, tFunction) else partConst
            }
        }
        if (parser.sawInterpolation) {
            if (parser.isInterpolationFold(start)) {
                // The compiler folded a constant expression in place of a single interpolation, e.g. a const val
                // reference. Such folds never merge with the surrounding template text, so the constant's value is
                // the interpolation's value and binds like any other interpolated argument.
                return listOf(wrapInT(irConst, receiver, tFunction))
            }
            reportUnsplittableConstant(irConst, start, end)
            return listOf(irConst)
        }
        return listOf(unverifiedConst(irConst, position, receiver, tFunction))
    }

    /**
     * The fallback for a string constant the source cannot vouch for. A constant whose source span is shorter than
     * its value cannot be a fragment of the template's literal text; as the interpolated argument of a string
     * template it yields a bind value, matching the treatment of non-constant arguments. In every other position
     * the constant stays text: chain operands are literal by the template rules, and standalone constants are
     * ordinary literals whose span includes the quotes.
     */
    private fun unverifiedConst(
        irConst: IrConst<*>,
        position: ConstPosition,
        receiver: IrValueParameter,
        tFunction: IrSimpleFunction,
    ): IrExpression {
        val value = irConst.value as String
        if (position == ConstPosition.TEMPLATE_ARGUMENT && irConst.endOffset - irConst.startOffset < value.length) {
            return wrapInT(irConst, receiver, tFunction)
        }
        return irConst
    }

    /** Creates a new string [IrConst] with the given offsets and value. */
    @Suppress("UNCHECKED_CAST")
    private fun createStringConst(startOffset: Int, endOffset: Int, type: IrType, value: String): IrConst<*> {
        return IrConstImpl.string(startOffset, endOffset, type, value) as IrConst<*>
    }

    /**
     * Checks whether an expression is already a call to `TemplateContext.t()` or `TemplateContext.interpolate()`, so we
     * don't double-wrap.
     */
    private fun isAlreadyWrappedInT(expression: IrExpression): Boolean {
        if (expression !is IrCall) return false
        val callee = expression.symbol.owner
        val calleeName = callee.name.asString()
        if (calleeName != "t" && calleeName != "interpolate") return false
        val dispatchReceiver = expression.dispatchReceiver ?: return false
        val dispatchFqName = dispatchReceiver.type.classFqName ?: return false
        return dispatchFqName == TEMPLATE_CONTEXT_FQN
    }

    /** Wraps an expression in a call to `TemplateContext.t(expr)`, generating IR equivalent to `receiver.t(expr)`. */
    private fun wrapInT(
        expression: IrExpression,
        receiver: IrValueParameter,
        tFunction: IrSimpleFunction,
    ): IrExpression {
        val builder = DeclarationIrBuilder(pluginContext, tFunction.symbol, expression.startOffset, expression.endOffset)
        return builder.irCall(tFunction).apply {
            dispatchReceiver = builder.irGet(receiver)
            putValueArgument(0, expression)
        }
    }

    /**
     * Injects a call to `receiver.autoInterpolation()` at the start of the lambda body, before any existing
     * statements. This signals to the runtime that the compiler plugin has processed this lambda.
     */
    private fun injectAutoInterpolationCall(
        function: org.jetbrains.kotlin.ir.declarations.IrFunction,
        receiver: IrValueParameter,
        autoInterpolation: IrSimpleFunction,
    ) {
        val body = function.body as? IrBlockBody ?: return
        val builder = DeclarationIrBuilder(pluginContext, function.symbol, function.startOffset, function.startOffset)
        val call = builder.irCall(autoInterpolation).apply {
            dispatchReceiver = builder.irGet(receiver)
        }
        body.statements.add(0, call)
    }

    /** Reports a compiler error for a folded constant the plugin cannot split into template text and values. */
    private fun reportUnsplittableConstant(irConst: IrConst<*>, start: Int, end: Int) {
        val snippet = currentParser?.snippet(start, end) ?: irConst.value.toString()
        report(
            CompilerMessageSeverity.ERROR,
            "Storm compiler plugin cannot determine which parts of this SQL template are text and which are " +
                "values: the Kotlin compiler folded a constant expression into the surrounding template text " +
                "($snippet). Interpolate the constant with an explicit t() or interpolate() call, or inline it " +
                "as a plain string literal.",
            irConst,
        )
    }

    /** Reports, once per file, that folded constants cannot be verified because the source is unreadable. */
    private fun reportUnverifiableTemplate(irConst: IrConst<*>) {
        if (unverifiableReported) return
        unverifiableReported = true
        report(
            CompilerMessageSeverity.WARNING,
            "Storm compiler plugin cannot read the source of ${currentFile?.fileEntry?.name} to verify constant " +
                "expressions folded into SQL templates; the folded constants are left as template text.",
            irConst,
        )
    }

    private fun report(severity: CompilerMessageSeverity, message: String, element: IrElement) {
        val fileEntry = currentFile?.fileEntry
        val location = if (fileEntry != null && element.startOffset >= 0) {
            CompilerMessageLocation.create(
                fileEntry.name,
                fileEntry.getLineNumber(element.startOffset) + 1,
                fileEntry.getColumnNumber(element.startOffset) + 1,
                null,
            )
        } else {
            null
        }
        messageCollector.report(severity, message, location)
    }

    /** Resolves the `TemplateContext.t(Any?): String` function symbol. */
    private fun resolveTFunction(templateContextClass: IrClass): IrSimpleFunction? {
        return templateContextClass.functions
            .firstOrNull { it.name.asString() == "t" && it.valueParameters.size == 1 }
    }

    /** Resolves the `TemplateContext.autoInterpolation()` function symbol. */
    private fun resolveAutoInterpolationFunction(templateContextClass: IrClass): IrSimpleFunction? {
        return templateContextClass.functions
            .firstOrNull { it.name.asString() == "autoInterpolation" && it.valueParameters.isEmpty() }
    }
}

/** Where a string constant sits relative to the template being processed. */
internal enum class ConstPosition {
    /** An argument of a string template literal. */
    TEMPLATE_ARGUMENT,

    /** An operand of a `+` chain. */
    CHAIN_OPERAND,

    /** A constant that is not part of any concatenation. */
    STANDALONE;

    companion object {
        fun of(operatorConcatenation: Boolean): ConstPosition =
            if (operatorConcatenation) CHAIN_OPERAND else TEMPLATE_ARGUMENT
    }
}

/**
 * A piece of a parsed template region. Offsets are absolute positions in the source file.
 */
internal sealed class TemplatePart {
    abstract val startOffset: Int
    abstract val endOffset: Int
    abstract val text: String
}

/** Literal SQL text. */
internal class TemplateText(
    override val startOffset: Int,
    override val endOffset: Int,
    override val text: String,
) : TemplatePart()

/** A folded constant expression that yields a bind value. */
internal class TemplateValue(
    override val startOffset: Int,
    override val endOffset: Int,
    override val text: String,
) : TemplatePart()

/**
 * Interpolation syntax of a string literal: the number of dollar signs in an interpolation marker and whether the
 * literal is raw (triple-quoted, no escape processing).
 */
internal data class LiteralKind(val dollars: Int, val raw: Boolean)

/**
 * Parses the source text behind a folded string constant back into the template pieces the constant was folded
 * from: literal text and `${"..."}` constant expressions.
 *
 * Parsing is driven purely by the source text; the caller verifies the reassembled text against the constant's
 * actual value, so a parse that would change the meaning of the template cannot go undetected. The parser handles
 * the shapes the compiler produces: bare template content, content that starts at the inner literal of an
 * interpolation whose `${` marker lies before the region, and complete quoted literals joined by `+`. Escape
 * sequences are decoded in regular literals and taken verbatim in raw literals, and multi-dollar literals only
 * treat runs of at least the marker's dollar count as interpolations.
 */
internal class TemplateSourceParser(private val fileText: String) {

    /**
     * Set when the most recent [parse] encountered interpolation syntax, even if parsing subsequently failed.
     * Distinguishes template-shaped source, whose failures must be loud, from plain constants.
     */
    var sawInterpolation: Boolean = false
        private set

    fun inBounds(offset: Int): Boolean = offset in 0..fileText.length

    /** Checks whether the source region [start] until [end] spells out [value] verbatim. */
    fun matchesSource(start: Int, end: Int, value: String): Boolean =
        end - start == value.length && fileText.regionMatches(start, value, 0, value.length)

    /** A condensed, quoted rendering of the source region for diagnostics. */
    fun snippet(start: Int, end: Int): String {
        val region = fileText.substring(start, end).replace("\n", "\\n")
        return if (region.length <= 60) "'$region'" else "'${region.take(57)}...'"
    }

    /** The literal kind of the string literal that starts at [offset], or null when the offset does not sit on one. */
    fun literalKindAt(offset: Int): LiteralKind? {
        if (offset < 0 || offset >= fileText.length) return null
        return literalPrefix(offset)?.first
    }

    /**
     * Checks whether the region at [start] is the folded form of a single interpolation: its source sits directly
     * inside interpolation syntax, `$name` or `${name}`, rather than starting at a string literal. The compiler
     * folds such constants in place of the interpolation without merging the surrounding template text, so the
     * constant's value is the interpolation's value. Merged constants always start at literal syntax instead.
     */
    fun isInterpolationFold(start: Int): Boolean {
        if (literalPrefix(start) != null) return false
        return markerBehind(start) != null || (start > 0 && fileText[start - 1] == '$')
    }

    /**
     * Parses the region [start] until [end] into template parts, or returns null when the region cannot be related
     * to template syntax. [enclosingKind] is the kind of the string literal the region belongs to, when known.
     */
    fun parse(start: Int, end: Int, enclosingKind: LiteralKind?): List<TemplatePart>? {
        sawInterpolation = false
        if (start < 0 || end < start || end > fileText.length) return null
        markerBehind(start)?.let { dollars ->
            // The region starts at the inner literal of an interpolation whose marker lies before it.
            sawInterpolation = true
            if (enclosingKind != null) {
                return parseContinuation(start, end, LiteralKind(dollars, enclosingKind.raw))
            }
            // The enclosing literal's kind is unknown; the value verification in the caller picks the attempt
            // that reproduces the constant.
            return parseContinuation(start, end, LiteralKind(dollars, raw = false))
                ?: parseContinuation(start, end, LiteralKind(dollars, raw = true))
        }
        if (start > 0 && fileText[start - 1] == '$') {
            // The region starts at the identifier of a simple-name interpolation like $CONST: the folded value
            // cannot be recovered from the source.
            sawInterpolation = true
            return null
        }
        if (literalPrefix(start) != null) {
            return parseLiteralChain(start, end)
        }
        val kind = enclosingKind ?: openingQuoteBehind(start) ?: return null
        val parts = mutableListOf<TemplatePart>()
        val consumed = scanContent(start, end, kind, parts, stopAtClosingQuote = false) ?: return null
        if (consumed != end) return null
        return parts
    }

    /** Parses a region that starts inside an interpolation: the inner literal, its closing brace, then content. */
    private fun parseContinuation(start: Int, end: Int, kind: LiteralKind): List<TemplatePart>? {
        val parts = mutableListOf<TemplatePart>()
        val pos = parseInterpolationTail(start, parts) ?: return null
        if (pos >= end) return parts // The region ends inside the interpolation's closing syntax.
        val consumed = scanContent(pos, end, kind, parts, stopAtClosingQuote = false) ?: return null
        if (consumed != end) return null
        return parts
    }

    /**
     * Parses the inner constant of an interpolation plus its closing brace, appending the constant as a value part.
     * The region of a folded constant can end anywhere inside the closing syntax, so the constant and the brace are
     * matched against the file rather than the region. Returns the position after the brace.
     */
    private fun parseInterpolationTail(start: Int, parts: MutableList<TemplatePart>): Int? {
        var pos = parseInnerConstant(start, parts) ?: return null
        while (pos < fileText.length && fileText[pos].isWhitespace()) pos++
        if (pos >= fileText.length || fileText[pos] != '}') return null
        return pos + 1
    }

    /**
     * Parses the constant expression of an interpolation and appends it as a single value part. Supported are the
     * literals whose string rendering can be derived from the source: strings, characters, booleans, and integers.
     * The rendering is verified against the folded value by the caller, so an unexpected rendering surfaces as an
     * unsplittable constant rather than a wrong split. Returns the position after the constant, or null when the
     * expression is not a supported literal.
     */
    private fun parseInnerConstant(start: Int, parts: MutableList<TemplatePart>): Int? {
        if (start >= fileText.length) return null
        if (literalPrefix(start) != null) {
            return parseInnerLiteral(start, parts)
        }
        if (fileText[start] == '\'') {
            return parseCharLiteral(start, parts)
        }
        for (keyword in listOf("true", "false")) {
            val end = start + keyword.length
            if (fileText.startsWith(keyword, start) && (end >= fileText.length || !isIdentifierPart(fileText[end]))) {
                parts.add(TemplateValue(start, end, keyword))
                return end
            }
        }
        return parseIntegerLiteral(start, parts)
    }

    /** Parses a character literal like `'c'` or `'\n'` and appends it as a value part. */
    private fun parseCharLiteral(start: Int, parts: MutableList<TemplatePart>): Int? {
        var pos = start + 1
        if (pos >= fileText.length) return null
        val text: String
        if (fileText[pos] == '\\') {
            val decoded = decodeEscape(pos, fileText.length) ?: return null
            text = decoded.first
            pos = decoded.second
        } else {
            text = fileText[pos].toString()
            pos++
        }
        if (pos >= fileText.length || fileText[pos] != '\'') return null
        parts.add(TemplateValue(start, pos + 1, text))
        return pos + 1
    }

    /** Parses an integer literal, decimal, hexadecimal, or binary, with optional sign and suffixes. */
    private fun parseIntegerLiteral(start: Int, parts: MutableList<TemplatePart>): Int? {
        var pos = start
        var sign = ""
        if (pos < fileText.length && fileText[pos] == '-') {
            sign = "-"
            pos++
        }
        if (pos >= fileText.length || !fileText[pos].isDigit()) return null
        val radix: Int
        val digitsStart: Int
        when {
            fileText.startsWith("0x", pos) || fileText.startsWith("0X", pos) -> {
                radix = 16
                digitsStart = pos + 2
            }
            fileText.startsWith("0b", pos) || fileText.startsWith("0B", pos) -> {
                radix = 2
                digitsStart = pos + 2
            }
            else -> {
                radix = 10
                digitsStart = pos
            }
        }
        pos = digitsStart
        val digits = StringBuilder()
        while (pos < fileText.length && (Character.digit(fileText[pos], radix) >= 0 || fileText[pos] == '_')) {
            if (fileText[pos] != '_') digits.append(fileText[pos])
            pos++
        }
        if (digits.isEmpty()) return null
        if (pos < fileText.length && (fileText[pos] == '.' || fileText[pos].lowercaseChar() in "ef")) {
            // A floating-point literal; its rendering is not derived here.
            return null
        }
        if (pos < fileText.length && (fileText[pos] == 'u' || fileText[pos] == 'U')) pos++
        if (pos < fileText.length && fileText[pos] == 'L') pos++
        parts.add(TemplateValue(start, pos, sign + java.math.BigInteger(digits.toString(), radix)))
        return pos
    }

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** Parses one or more complete quoted literals joined by `+`, the shape of a folded operator chain. */
    private fun parseLiteralChain(start: Int, end: Int): List<TemplatePart>? {
        val parts = mutableListOf<TemplatePart>()
        var pos = start
        while (true) {
            val (kind, contentStart) = literalPrefix(pos) ?: return null
            pos = scanContent(contentStart, end, kind, parts, stopAtClosingQuote = true) ?: return null
            while (pos < end && fileText[pos].isWhitespace()) pos++
            if (pos == end) return parts
            if (fileText[pos] != '+') return null
            pos++
            while (pos < end && fileText[pos].isWhitespace()) pos++
        }
    }

    /**
     * Parses the string literal of an interpolation and appends it as a single value part. The literal is scanned
     * against the whole file because a folded constant's region can end before the literal's closing quote. Returns
     * the position after the closing quote, or null when the interpolated expression is not a string literal.
     */
    private fun parseInnerLiteral(start: Int, parts: MutableList<TemplatePart>): Int? {
        val (kind, contentStart) = literalPrefix(start) ?: return null
        val inner = mutableListOf<TemplatePart>()
        val afterContent = scanContent(contentStart, fileText.length, kind, inner, stopAtClosingQuote = true, allowMarkers = false)
            ?: return null
        parts.add(TemplateValue(start, afterContent, inner.joinToString("") { it.text }))
        return afterContent
    }

    /**
     * Scans literal content, decoding escape sequences and splitting out `${"..."}` interpolations, and appends the
     * resulting text and value parts to [parts]. Returns the position after the content: after the closing quotes
     * when [stopAtClosingQuote] is set, [limit] otherwise. Returns null when the content cannot be a folded
     * constant template, e.g. an interpolation of anything but a string literal.
     */
    private fun scanContent(
        start: Int,
        limit: Int,
        kind: LiteralKind,
        parts: MutableList<TemplatePart>,
        stopAtClosingQuote: Boolean,
        allowMarkers: Boolean = true,
    ): Int? {
        val chunk = StringBuilder()
        var chunkStart = start
        var pos = start

        fun flushChunk(endOffset: Int) {
            if (chunk.isNotEmpty()) {
                parts.add(TemplateText(chunkStart, endOffset, chunk.toString()))
                chunk.setLength(0)
            }
        }

        while (pos < limit) {
            val c = fileText[pos]
            if (c == '"' && stopAtClosingQuote) {
                var runEnd = pos
                while (runEnd < limit && fileText[runEnd] == '"') runEnd++
                val runLength = runEnd - pos
                if (!kind.raw) {
                    flushChunk(pos)
                    return pos + 1
                }
                if (runLength >= 3) {
                    // The final three quotes close a raw literal; preceding quotes in the run are content.
                    repeat(runLength - 3) { chunk.append('"') }
                    flushChunk(runEnd - 3)
                    return runEnd
                }
                repeat(runLength) { chunk.append('"') }
                pos = runEnd
                continue
            }
            if (c == '\\' && !kind.raw) {
                val decoded = decodeEscape(pos, limit) ?: return null
                chunk.append(decoded.first)
                pos = decoded.second
                continue
            }
            if (c == '$') {
                var runEnd = pos
                while (runEnd < limit && fileText[runEnd] == '$') runEnd++
                val dollars = runEnd - pos
                val next = if (runEnd < limit) fileText[runEnd] else ' '
                if (dollars >= kind.dollars && (next == '{' || isIdentifierStart(next))) {
                    sawInterpolation = true
                    if (!allowMarkers) return null
                    if (next != '{') {
                        // Simple-name interpolation: the folded value cannot be recovered from the source.
                        return null
                    }
                    // Dollars beyond the marker's count are literal text before the marker.
                    repeat(dollars - kind.dollars) { chunk.append('$') }
                    flushChunk(pos + (dollars - kind.dollars))
                    var innerPos = runEnd + 1
                    while (innerPos < fileText.length && fileText[innerPos].isWhitespace()) innerPos++
                    pos = parseInterpolationTail(innerPos, parts) ?: return null
                    chunkStart = pos
                    if (pos >= limit) {
                        // The region ends inside the interpolation's closing syntax.
                        return if (stopAtClosingQuote) null else limit
                    }
                    continue
                }
                repeat(dollars) { chunk.append('$') }
                pos = runEnd
                continue
            }
            chunk.append(c)
            pos++
        }
        if (stopAtClosingQuote) return null // Truncated literal: the closing quote lies beyond the region.
        flushChunk(limit)
        return limit
    }

    /** Recognizes the dollars-and-quotes prefix of a string literal at [pos], e.g. `"`, `"""`, or `$$"`. */
    private fun literalPrefix(pos: Int): Pair<LiteralKind, Int>? {
        var p = pos
        while (p < fileText.length && fileText[p] == '$') p++
        val dollars = p - pos
        if (p >= fileText.length || fileText[p] != '"') return null
        val raw = fileText.startsWith("\"\"\"", p)
        return LiteralKind(maxOf(dollars, 1), raw) to p + if (raw) 3 else 1
    }

    /** The number of marker dollars when the characters directly before [start] are `$`-run plus `{`, or null. */
    private fun markerBehind(start: Int): Int? {
        if (start < 2 || fileText[start - 1] != '{') return null
        var p = start - 2
        while (p >= 0 && fileText[p] == '$') p--
        val dollars = start - 2 - p
        return if (dollars >= 1) dollars else null
    }

    /** The literal kind when [start] sits directly after an opening quote, or null. */
    private fun openingQuoteBehind(start: Int): LiteralKind? {
        var p = start - 1
        while (p >= 0 && fileText[p] == '"') p--
        val quotes = start - 1 - p
        if (quotes != 1 && quotes < 3) return null
        var d = p
        while (d >= 0 && fileText[d] == '$') d--
        return LiteralKind(maxOf(p - d, 1), raw = quotes >= 3)
    }

    /** Decodes the escape sequence at [pos]. Returns the decoded text and the position after the sequence. */
    private fun decodeEscape(pos: Int, limit: Int): Pair<String, Int>? {
        if (pos + 1 >= limit) return null
        return when (fileText[pos + 1]) {
            't' -> "\t" to pos + 2
            'b' -> "\b" to pos + 2
            'n' -> "\n" to pos + 2
            'r' -> "\r" to pos + 2
            '\'' -> "'" to pos + 2
            '"' -> "\"" to pos + 2
            '\\' -> "\\" to pos + 2
            '$' -> "$" to pos + 2
            'u' -> {
                if (pos + 6 > limit) return null
                val code = fileText.substring(pos + 2, pos + 6).toIntOrNull(16) ?: return null
                code.toChar().toString() to pos + 6
            }
            else -> null
        }
    }

    private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '`'
}
