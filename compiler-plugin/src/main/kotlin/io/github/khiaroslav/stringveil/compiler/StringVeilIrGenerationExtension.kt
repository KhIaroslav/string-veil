@file:OptIn(
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
    io.github.khiaroslav.stringveil.format.InternalStringVeilApi::class,
)

package io.github.khiaroslav.stringveil.compiler

import io.github.khiaroslav.stringveil.format.StringVeilFormat.MAX_REPETITIONS
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class StringVeilIrGenerationExtension(
    private val messageCollector: MessageCollector,
    private val nativeAvailable: Boolean = false,
    private val failOnSecretLikeLiterals: Boolean = false,
    private val cipher: StringCipher = LayeredStringCipher(),
) : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        val sourceFile = moduleFragment.files.firstOrNull() ?: return
        val symbols = RuntimeSymbols.resolve(pluginContext, sourceFile)
        if (symbols == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "string-veil: failed to resolve the Kotlin intArrayOf builtin.",
            )
            return
        }

        val transformer = StringLiteralTransformer(
            pluginContext = pluginContext,
            symbols = symbols,
            cipher = cipher,
            messageCollector = messageCollector,
            nativeAvailable = nativeAvailable,
            failOnSecretLikeLiterals = failOnSecretLikeLiterals,
        )
        moduleFragment.transformChildrenVoid(transformer)

        messageCollector.report(
            CompilerMessageSeverity.LOGGING,
                "string-veil: transformed=${transformer.transformed}, skipped=${transformer.skipped}, " +
                "layers=${transformer.configuredLayers}, methods=${transformer.methodSummary}, " +
                "engines=${transformer.engineSummary}",
        )
    }
}

private class StringLiteralTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: RuntimeSymbols,
    private val cipher: StringCipher,
    private val messageCollector: MessageCollector,
    private val nativeAvailable: Boolean,
    private val failOnSecretLikeLiterals: Boolean,
) : IrElementTransformerVoidWithContext() {
    var transformed: Int = 0
        private set

    var skipped: Int = 0
        private set

    var configuredLayers: Int = 0
        private set

    private val methodCounts: MutableMap<ProtectionMethod, Int> = mutableMapOf()
    val methodSummary: String
        get() = ProtectionMethod.entries
            .mapNotNull { method -> methodCounts[method]?.let { "$method:$it" } }
            .joinToString("|")

    private val engineCounts: MutableMap<ProtectionEngine, Int> = mutableMapOf()
    val engineSummary: String
        get() = ProtectionEngine.entries
            .mapNotNull { engine -> engineCounts[engine]?.let { "$engine:$it" } }
            .joinToString("|")

    private var scope: Scope = Scope.NONE
    private var expressionAnnotationResolver: SourceExpressionAnnotationResolver? = null
    private var inConstInitializer: Boolean = false

    // Source offsets of every `@Obfuscate` handled in the current file, through either the
    // declaration (IR) path or the expression (source-recovery) path. Any `@Obfuscate` the source
    // contains that is not in this set was applied to nothing — a silent plaintext leak — and the
    // per-file cross-check in visitFileNew fails the build for it.
    private var handledObfuscateOffsets: MutableSet<Int> = mutableSetOf()

    override fun visitFileNew(declaration: IrFile): IrFile {
        val previousResolver = expressionAnnotationResolver
        val previousHandled = handledObfuscateOffsets
        val fileName = declaration.fileEntry.name
        val resolver = SourceExpressionAnnotationResolver.fromFile(fileName)
        if (resolver == null && isReadableSourceFile(fileName)) {
            // Fail closed: we could not recover source-retained expression annotations that K2 drops
            // from IR, so a literal annotated at an expression site would silently ship as plaintext.
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "string-veil: could not read '$fileName' to recover expression-level @Obfuscate " +
                    "annotations; a literal annotated at an expression site would be emitted as " +
                    "plaintext. Fix the file, or move the annotation onto the enclosing declaration.",
            )
        }
        expressionAnnotationResolver = resolver
        handledObfuscateOffsets = mutableSetOf()

        val result = super.visitFileNew(declaration)

        reportUnappliedObfuscateAnnotations(declaration, resolver)

        expressionAnnotationResolver = previousResolver
        handledObfuscateOffsets = previousHandled
        return result
    }

    // Fail closed on every `@Obfuscate` in the file that was applied to no string literal — an
    // `@Obfuscate` on a compound expression (an `if`/`when`, an interpolated template, a call) that
    // K2 dropped from IR and the source recovery could not map to a literal. Left unreported, such a
    // literal ships as plaintext while the developer believes it is hidden.
    private fun reportUnappliedObfuscateAnnotations(
        file: IrFile,
        resolver: SourceExpressionAnnotationResolver?,
    ) {
        resolver ?: return
        val unapplied = resolver.obfuscateAnnotationOffsets() - handledObfuscateOffsets
        val fileEntry = file.fileEntry
        unapplied.sorted().forEach { offset ->
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "string-veil: this @Obfuscate was not applied to any string literal. String Veil " +
                    "obfuscates string literals, so annotating a compound expression (an if/when, an " +
                    "interpolated template, a call) protects nothing. Annotate the string literal " +
                    "directly, or move @Obfuscate onto the enclosing declaration.",
                CompilerMessageLocation.create(
                    fileEntry.name,
                    fileEntry.getLineNumber(offset) + 1,
                    fileEntry.getColumnNumber(offset) + 1,
                    null,
                ),
            )
        }
    }

    override fun visitClassNew(declaration: IrClass): IrStatement =
        withinStringScope(declaration) { super.visitClassNew(declaration) }

    override fun visitFunctionNew(declaration: IrFunction): IrStatement =
        withinStringScope(declaration) { super.visitFunctionNew(declaration) }

    override fun visitPropertyNew(declaration: IrProperty): IrStatement =
        withinStringScope(declaration) { super.visitPropertyNew(declaration) }

    override fun visitFieldNew(declaration: IrField): IrStatement {
        val wasConst = inConstInitializer
        inConstInitializer = declaration.correspondingPropertySymbol?.owner?.isConst == true
        return try {
            withinStringScope(declaration) { super.visitFieldNew(declaration) }
        } finally {
            inConstInitializer = wasConst
        }
    }

    override fun visitAnnotation(expression: IrAnnotation): IrExpression = expression

    override fun visitConst(expression: IrConst): IrExpression {
        if (
            expression.kind != IrConstKind.String ||
            expression.startOffset < 0 ||
            expression.endOffset < expression.startOffset
        ) {
            return expression
        }

        val annotations = expressionAnnotationResolver
            ?.annotationsAt(expression.startOffset, expression.endOffset)
            .orEmpty()
        val expressionConfig = expressionAnnotationResolver
            ?.obfuscationConfigAt(expression.startOffset, expression.endOffset)
        val expressionExcluded = DO_NOT_OBFUSCATE_FQ_NAME in annotations
        val expressionObfuscated = OBFUSCATE_FQ_NAME in annotations
        if (expressionObfuscated) {
            // This @Obfuscate was mapped to a literal, so it is handled even if the literal is later
            // skipped (empty) — the per-file cross-check must not flag it as a leak.
            expressionAnnotationResolver
                ?.matchedObfuscateOffsetAt(expression.startOffset, expression.endOffset)
                ?.let(handledObfuscateOffsets::add)
        }
        val shouldTransform = when {
            scope.mode == ScopeMode.EXCLUDE -> false
            expressionExcluded -> false
            scope.mode == ScopeMode.OBFUSCATE -> true
            expressionObfuscated -> true
            else -> false
        }

        if (!shouldTransform) {
            if (scope.mode == ScopeMode.EXCLUDE || expressionExcluded) skipped++
            return expression
        }

        val value = expression.value as String
        if (value.isEmpty()) {
            skipped++
            return expression
        }

        if (inConstInitializer) {
            // A `const val` must keep a compile-time-constant initializer; replacing it with a
            // decode() call makes the backend fail. Report it clearly instead of leaking or crashing.
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "string-veil: @Obfuscate cannot protect a `const val` — its value must stay a " +
                    "compile-time constant. Remove `const`, or exclude it with @DoNotObfuscate.",
                secretWarningLocation(expression),
            )
            skipped++
            return expression
        }

        SecretLiteralHeuristics.detect(value)?.let { reason ->
            messageCollector.report(
                if (failOnSecretLikeLiterals) {
                    CompilerMessageSeverity.ERROR
                } else {
                    CompilerMessageSeverity.WARNING
                },
                "string-veil: this @Obfuscate literal looks like $reason. String Veil is obfuscation, " +
                    "not encryption or a secrets store, and cannot keep a real secret safe on the " +
                    "client (see SECURITY.md). Move it server-side or inject it at runtime.",
                secretWarningLocation(expression),
            )
            if (failOnSecretLikeLiterals) {
                skipped++
                return expression
            }
        }

        val config = expressionConfig ?: scope.config
        val engine = when (config.engine) {
            ProtectionEngine.AUTO -> if (nativeAvailable) ProtectionEngine.NATIVE else ProtectionEngine.JVM
            ProtectionEngine.JVM -> ProtectionEngine.JVM
            ProtectionEngine.NATIVE -> ProtectionEngine.NATIVE
        }
        when (engine) {
            ProtectionEngine.NATIVE -> if (
                symbols.nativeDecoderClass == null || symbols.nativeDecode == null
            ) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "string-veil: NATIVE engine requires an Android compilation with native-runtime.",
                )
                return expression
            }
            ProtectionEngine.JVM -> if (symbols.decoderClass == null || symbols.decode == null) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "string-veil: JVM engine requires the string-veil runtime dependency.",
                )
                return expression
            }
            ProtectionEngine.AUTO -> error("AUTO engine must be resolved before symbol validation")
        }
        val encrypted = cipher.encrypt(
            value = value.encodeToByteArray(),
            context = EncryptionContext(
                fileName = currentFile.fileEntry.name,
                startOffset = expression.startOffset,
            ),
            config = config,
        )
        transformed++
        configuredLayers += config.repetitions
        methodCounts[config.method] = methodCounts.getOrDefault(config.method, 0) + 1
        engineCounts[engine] = engineCounts.getOrDefault(engine, 0) + 1

        return buildDecoderCall(expression, encrypted, engine)
    }

    private fun buildDecoderCall(
        source: IrConst,
        encrypted: EncryptedString,
        engine: ProtectionEngine,
    ): IrExpression {
        val builder = DeclarationIrBuilder(
            pluginContext,
            checkNotNull(currentScope).scope.scopeOwnerSymbol,
            source.startOffset,
            source.endOffset,
        )

        val decoderClass = if (engine == ProtectionEngine.NATIVE) {
            checkNotNull(symbols.nativeDecoderClass)
        } else {
            checkNotNull(symbols.decoderClass)
        }
        val decode = if (engine == ProtectionEngine.NATIVE) {
            checkNotNull(symbols.nativeDecode)
        } else {
            checkNotNull(symbols.decode)
        }

        return builder.irCall(decode).apply {
            if (engine != ProtectionEngine.NATIVE) {
                dispatchReceiver = builder.irGetObject(decoderClass)
            }
            val parameters = symbol.owner.parameters.filter { it.kind == IrParameterKind.Regular }
            arguments[parameters.single()] = builder.irIntArray(encrypted.container)
        }
    }

    private fun isReadableSourceFile(fileName: String): Boolean =
        runCatching { Path.of(fileName) }.getOrNull()?.let(Files::isRegularFile) == true

    private fun secretWarningLocation(expression: IrConst): CompilerMessageLocation? {
        val fileEntry = currentFile.fileEntry
        return CompilerMessageLocation.create(
            fileEntry.name,
            fileEntry.getLineNumber(expression.startOffset) + 1,
            fileEntry.getColumnNumber(expression.startOffset) + 1,
            null,
        )
    }

    private fun DeclarationIrBuilder.irIntArray(values: IntArray): IrExpression =
        irCall(symbols.intArrayOf).apply {
            val parameter = symbol.owner.parameters.single { it.kind == IrParameterKind.Regular }
            arguments[parameter] = IrVarargImpl(
                startOffset,
                endOffset,
                pluginContext.irBuiltIns.intArray.defaultType,
                pluginContext.irBuiltIns.intType,
                values.map { irInt(it) },
            )
        }

    private fun <T> withinStringScope(
        declaration: IrDeclarationBase,
        block: () -> T,
    ): T {
        val previousScope = scope
        scope = declaration.resolveScope(previousScope)
        return try {
            block()
        } finally {
            scope = previousScope
        }
    }

    private fun IrDeclarationBase.resolveScope(parentScope: Scope): Scope =
        when {
            parentScope.mode == ScopeMode.EXCLUDE -> Scope.EXCLUDE
            hasAnnotation(DO_NOT_OBFUSCATE_FQ_NAME) -> Scope.EXCLUDE
            hasAnnotation(OBFUSCATE_FQ_NAME) -> {
                // Record the source position so the per-file cross-check knows this @Obfuscate was
                // handled through the declaration (IR) path and must not be reported as a leak.
                getAnnotation(OBFUSCATE_FQ_NAME)
                    ?.startOffset
                    ?.takeIf { it >= 0 }
                    ?.let(handledObfuscateOffsets::add)
                Scope.obfuscate(obfuscationConfig())
            }
            else -> parentScope
        }

    private fun IrDeclarationBase.obfuscationConfig(): ProtectionConfig {
        val annotation = getAnnotation(OBFUSCATE_FQ_NAME)
            ?: return ProtectionConfig()
        fun argument(name: Name): IrExpression? = annotation.symbol.owner.parameters
            .firstOrNull { it.name == name }
            ?.let { annotation.arguments[it.indexInParameters] }

        val method = argument(METHOD_ARGUMENT)
            .enumMethodOrNull()
            ?: ProtectionMethod.RANDOM_ALL
        val repetitions = (argument(REPETITIONS_ARGUMENT) as? IrConst)
            ?.value as? Int
            ?: ProtectionConfig().repetitions
        val methods = (argument(METHODS_ARGUMENT) as? IrVararg)
            ?.elements
            ?.mapNotNull { (it as? IrGetEnumValue).enumMethodOrNull() }
            ?.toSet()
            .orEmpty()
        val engine = argument(ENGINE_ARGUMENT)
            .enumEngineOrNull()
            ?: ProtectionEngine.AUTO
        return ProtectionConfig(
            method = method,
            methods = methods,
            repetitions = repetitions.coerceIn(1, MAX_REPETITIONS),
            engine = engine,
        )
    }

    private fun IrExpression?.enumMethodOrNull(): ProtectionMethod? {
        val name = (this as? IrGetEnumValue)?.symbol?.owner?.name?.asString() ?: return null
        return ProtectionMethod.entries.firstOrNull { it.name == name }
    }

    private fun IrExpression?.enumEngineOrNull(): ProtectionEngine? {
        val name = (this as? IrGetEnumValue)?.symbol?.owner?.name?.asString() ?: return null
        return ProtectionEngine.entries.firstOrNull { it.name == name }
    }

    private data class Scope(
        val mode: ScopeMode,
        val config: ProtectionConfig = ProtectionConfig(),
    ) {
        companion object {
            val NONE = Scope(ScopeMode.NONE)
            val EXCLUDE = Scope(ScopeMode.EXCLUDE)
            fun obfuscate(config: ProtectionConfig): Scope = Scope(ScopeMode.OBFUSCATE, config)
        }
    }

    private enum class ScopeMode {
        NONE,
        OBFUSCATE,
        EXCLUDE,
    }
}

private data class RuntimeSymbols(
    val decoderClass: IrClassSymbol?,
    val decode: IrSimpleFunctionSymbol?,
    val nativeDecoderClass: IrClassSymbol?,
    val nativeDecode: IrSimpleFunctionSymbol?,
    val intArrayOf: IrSimpleFunctionSymbol,
) {
    companion object {
        fun resolve(
            pluginContext: IrPluginContext,
            sourceFile: IrFile,
        ): RuntimeSymbols? {
            val sourceFinder = pluginContext.finderForSource(sourceFile)
            val decoderClass = sourceFinder.findClass(DECODER_CLASS_ID)
            val decode = sourceFinder.findFunctions(DECODE_CALLABLE_ID)
                .singleOrNull {
                    it.owner.parameters.count { parameter ->
                        parameter.kind == IrParameterKind.Regular
                    } == 1
                }
            val nativeDecoderClass = sourceFinder.findClass(NATIVE_DECODER_CLASS_ID)
            val nativeDecode = sourceFinder.findFunctions(NATIVE_DECODE_CALLABLE_ID)
                .singleOrNull {
                    it.owner.parameters.count { parameter ->
                        parameter.kind == IrParameterKind.Regular
                    } == 1
                }
                ?: nativeDecoderClass
                    ?.owner
                    ?.declarations
                    ?.filterIsInstance<IrSimpleFunction>()
                    ?.singleOrNull {
                        it.name == DECODE_NAME &&
                            it.parameters.count { parameter ->
                                parameter.kind == IrParameterKind.Regular
                            } == 1
                    }
                    ?.symbol
            val intArrayOf = pluginContext.finderForBuiltins()
                .findFunctions(INT_ARRAY_OF_CALLABLE_ID)
                .singleOrNull()
                ?: return null

            return RuntimeSymbols(
                decoderClass = decoderClass,
                decode = decode,
                nativeDecoderClass = nativeDecoderClass,
                nativeDecode = nativeDecode,
                intArrayOf = intArrayOf,
            )
        }
    }
}

private val OBFUSCATE_FQ_NAME =
    FqName("io.github.khiaroslav.stringveil.annotations.Obfuscate")
private val DO_NOT_OBFUSCATE_FQ_NAME =
    FqName("io.github.khiaroslav.stringveil.annotations.DoNotObfuscate")
private val DECODER_CLASS_ID =
    ClassId.topLevel(FqName("io.github.khiaroslav.stringveil.runtime.StringDecoder"))
private val DECODE_CALLABLE_ID =
    CallableId(DECODER_CLASS_ID, Name.identifier("decode"))
private val NATIVE_DECODER_CLASS_ID =
    ClassId.topLevel(FqName("io.github.khiaroslav.stringveil.runtime.NativeStringDecoder"))
private val DECODE_NAME = Name.identifier("decode")
private val NATIVE_DECODE_CALLABLE_ID =
    CallableId(NATIVE_DECODER_CLASS_ID, DECODE_NAME)
private val INT_ARRAY_OF_CALLABLE_ID =
    CallableId(FqName("kotlin"), Name.identifier("intArrayOf"))
private val METHOD_ARGUMENT = Name.identifier("method")
private val METHODS_ARGUMENT = Name.identifier("methods")
private val REPETITIONS_ARGUMENT = Name.identifier("repetitions")
private val ENGINE_ARGUMENT = Name.identifier("engine")
