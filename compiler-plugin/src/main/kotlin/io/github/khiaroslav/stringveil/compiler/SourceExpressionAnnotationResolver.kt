@file:OptIn(io.github.khiaroslav.stringveil.format.InternalStringVeilApi::class)

package io.github.khiaroslav.stringveil.compiler

import io.github.khiaroslav.stringveil.format.StringVeilFormat.MAX_REPETITIONS
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName

/**
 * Recovers source-retained expression annotations that K2 does not copy to IR.
 *
 * The Kotlin lexer is used instead of text-only matching, so annotation-like text inside comments
 * cannot mark a literal. Method, selected-method, and repetition arguments are recovered too.
 */
internal class SourceExpressionAnnotationResolver private constructor(
    private val source: String,
) {
    private val tokens: List<Token> = source.tokens()
    private val packageFqName: String? = parsePackageFqName()
    private val imports: List<Import> = parseImports()

    fun annotationsAt(
        startOffset: Int,
        endOffset: Int,
    ): Set<FqName> = annotationsWithArgumentsAt(startOffset, endOffset)
        .mapTo(mutableSetOf()) { it.fqName }

    fun obfuscationConfigAt(
        startOffset: Int,
        endOffset: Int,
    ): ProtectionConfig? {
        val annotation = annotationsWithArgumentsAt(startOffset, endOffset)
            .firstOrNull { it.fqName == OBFUSCATE_FQ_NAME }
            ?: return null
        val arguments = annotation.arguments ?: return ProtectionConfig()
        val methodName = METHOD_ARGUMENT_REGEX.find(arguments)?.groupValues?.last()
            ?: POSITIONAL_METHOD_REGEX.find(arguments)?.groupValues?.last()
        val method = methodName?.let(::protectionMethod) ?: ProtectionMethod.RANDOM_ALL
        val repetitions = REPETITIONS_ARGUMENT_REGEX.find(arguments)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_REPETITIONS)
            ?: ProtectionConfig().repetitions
        val methods = METHODS_ARGUMENT_REGEX.find(arguments)
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.mapNotNull(::protectionMethod)
            ?.toSet()
            .orEmpty()
        val engine = ENGINE_ARGUMENT_REGEX.find(arguments)
            ?.groupValues
            ?.last()
            ?.let(::protectionEngine)
            ?: ProtectionEngine.AUTO
        return ProtectionConfig(method, methods, repetitions, engine)
    }

    private fun annotationsWithArgumentsAt(
        startOffset: Int,
        endOffset: Int,
    ): List<ResolvedAnnotation> {
        if (startOffset < 0 || endOffset < startOffset) return emptyList()

        val quoteIndex = tokens.indexOfFirst { token ->
            token.type == KtTokens.OPEN_QUOTE &&
                token.startOffset >= startOffset &&
                token.startOffset < endOffset
        }
        if (quoteIndex < 1) return emptyList()

        val result = mutableListOf<ResolvedAnnotation>()
        var cursor = quoteIndex - 1

        while (cursor >= 0) {
            val annotation = parseAnnotationEndingAt(cursor) ?: break
            resolve(annotation.name)?.let { fqName ->
                result += ResolvedAnnotation(fqName, annotation.arguments)
            }
            cursor = annotation.beforeAtIndex
        }

        return result
    }

    private fun parseAnnotationEndingAt(endIndex: Int): ParsedAnnotation? {
        var cursor = endIndex
        var arguments: String? = null

        if (tokens[cursor].type == KtTokens.RPAR) {
            val closeParenthesis = cursor
            var nesting = 1
            cursor--
            while (cursor >= 0 && nesting > 0) {
                when (tokens[cursor].type) {
                    KtTokens.RPAR -> nesting++
                    KtTokens.LPAR -> nesting--
                }
                if (nesting > 0) cursor--
            }
            if (cursor < 0 || tokens[cursor].type != KtTokens.LPAR) return null
            arguments = tokens.subList(cursor + 1, closeParenthesis).joinToString("") { it.text }
            cursor--
        }

        if (cursor < 0 || tokens[cursor].type != KtTokens.IDENTIFIER) return null

        val nameParts = mutableListOf(tokens[cursor].normalizedIdentifier())
        cursor--

        while (
            cursor >= 1 &&
            tokens[cursor].type == KtTokens.DOT &&
            tokens[cursor - 1].type == KtTokens.IDENTIFIER
        ) {
            nameParts.add(0, tokens[cursor - 1].normalizedIdentifier())
            cursor -= 2
        }

        if (cursor < 0 || tokens[cursor].type != KtTokens.AT) return null

        return ParsedAnnotation(
            name = nameParts.joinToString("."),
            beforeAtIndex = cursor - 1,
            arguments = arguments,
        )
    }

    private fun resolve(sourceName: String): FqName? {
        if ('.' in sourceName) {
            return STRING_VEIL_ANNOTATIONS.firstOrNull { it.asString() == sourceName }
        }

        imports.firstOrNull { it.sourceName == sourceName }
            ?.fqName
            ?.let { imported ->
                return STRING_VEIL_ANNOTATIONS.firstOrNull { it.asString() == imported }
            }

        packageFqName?.let { packageName ->
            val samePackageName = "$packageName.$sourceName"
            return STRING_VEIL_ANNOTATIONS.firstOrNull { it.asString() == samePackageName }
        }

        return null
    }

    private fun parsePackageFqName(): String? =
        tokens.firstOrNull { it.text == "package" }
            ?.sourceLine()
            ?.let(PACKAGE_REGEX::matchEntire)
            ?.groupValues
            ?.get(1)

    private fun parseImports(): List<Import> =
        tokens.asSequence()
            .filter { it.text == "import" }
            .mapNotNull { token ->
                val match = IMPORT_REGEX.matchEntire(token.sourceLine()) ?: return@mapNotNull null
                val imported = match.groupValues[1]
                val alias = match.groupValues[2].ifBlank { imported.substringAfterLast('.') }

                if (imported.endsWith(".*")) {
                    STRING_VEIL_ANNOTATIONS
                        .filter { it.parent().asString() == imported.removeSuffix(".*") }
                        .map { Import(sourceName = it.shortName().asString(), fqName = it.asString()) }
                } else {
                    listOf(Import(sourceName = alias, fqName = imported))
                }
            }
            .flatten()
            .toList()

    private fun Token.sourceLine(): String {
        val end = source.indexOf('\n', startOffset).let { if (it < 0) source.length else it }
        return source.substring(startOffset, end)
            .substringBefore("//")
            .substringBefore("/*")
            .trim()
    }

    private fun String.tokens(): List<Token> {
        val lexer = KotlinLexer()
        val result = mutableListOf<Token>()
        lexer.start(this)

        while (lexer.tokenType != null) {
            val type = checkNotNull(lexer.tokenType)
            if (type !in IGNORED_TOKEN_TYPES) {
                result += Token(
                    type = type,
                    startOffset = lexer.tokenStart,
                    text = substring(lexer.tokenStart, lexer.tokenEnd),
                )
            }
            lexer.advance()
        }

        return result
    }

    private fun Token.normalizedIdentifier(): String = text.removeSurrounding("`")

    private data class Token(
        val type: IElementType,
        val startOffset: Int,
        val text: String,
    )

    private data class ParsedAnnotation(
        val name: String,
        val beforeAtIndex: Int,
        val arguments: String?,
    )

    private data class ResolvedAnnotation(
        val fqName: FqName,
        val arguments: String?,
    )

    private data class Import(
        val sourceName: String,
        val fqName: String,
    )

    companion object {
        fun fromFile(fileName: String): SourceExpressionAnnotationResolver? {
            val path = runCatching { Path.of(fileName) }.getOrNull() ?: return null
            if (!Files.isRegularFile(path)) return null

            return runCatching {
                SourceExpressionAnnotationResolver(Files.readString(path))
            }.getOrNull()
        }

        private val OBFUSCATE_FQ_NAME =
            FqName("io.github.khiaroslav.stringveil.annotations.Obfuscate")
        private val DO_NOT_OBFUSCATE_FQ_NAME =
            FqName("io.github.khiaroslav.stringveil.annotations.DoNotObfuscate")
        private val STRING_VEIL_ANNOTATIONS =
            setOf(OBFUSCATE_FQ_NAME, DO_NOT_OBFUSCATE_FQ_NAME)
        private val IGNORED_TOKEN_TYPES =
            setOf(
                KtTokens.WHITE_SPACE,
                KtTokens.BLOCK_COMMENT,
                KtTokens.EOL_COMMENT,
                KtTokens.SHEBANG_COMMENT,
                KtTokens.DOC_COMMENT,
            )
        private val PACKAGE_REGEX = Regex("""package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*""")
        private val IMPORT_REGEX = Regex(
            """import\s+([A-Za-z_][A-Za-z0-9_.]*(?:\.\*)?)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?\s*""",
        )
        private val METHOD_ARGUMENT_REGEX =
            Regex("""(?:^|,)method=((?:[A-Za-z_][A-Za-z0-9_]*\.)*)([A-Za-z_][A-Za-z0-9_]*)""")
        private val POSITIONAL_METHOD_REGEX =
            Regex("""^((?:[A-Za-z_][A-Za-z0-9_]*\.)*)([A-Za-z_][A-Za-z0-9_]*)""")
        private val REPETITIONS_ARGUMENT_REGEX = Regex("""(?:^|,)repetitions=(\d+)""")
        private val METHODS_ARGUMENT_REGEX = Regex("""(?:^|,)methods=\[([^]]*)]""")
        private val ENGINE_ARGUMENT_REGEX =
            Regex("""(?:^|,)engine=((?:[A-Za-z_][A-Za-z0-9_]*\.)*)([A-Za-z_][A-Za-z0-9_]*)""")

        private fun protectionMethod(source: String): ProtectionMethod? {
            val name = source.trim().substringAfterLast('.')
            return ProtectionMethod.entries.firstOrNull { it.name == name }
        }

        private fun protectionEngine(source: String): ProtectionEngine? {
            val name = source.trim().substringAfterLast('.')
            return ProtectionEngine.entries.firstOrNull { it.name == name }
        }
    }
}
