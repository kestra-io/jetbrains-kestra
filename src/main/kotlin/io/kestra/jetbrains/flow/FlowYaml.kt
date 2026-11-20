package io.kestra.jetbrains.flow

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLBlockScalar
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

// Reads flows from the YAML PSI, which tolerates the half-typed documents an editor holds.
object FlowYaml {
    fun root(file: PsiFile): YAMLMapping? =
        (file as? YAMLFile)?.documents?.firstOrNull()?.topLevelValue as? YAMLMapping

    // A flow declares id, namespace, and tasks or triggers.
    fun isFlow(file: PsiFile): Boolean {
        val root = root(file) ?: return false
        fun present(key: String) = root.getKeyValueByKey(key)?.value.let { it != null && !(it is YAMLScalar && it.textValue.isBlank()) }
        return present("id") && present("namespace") && (present("tasks") || present("triggers"))
    }

    fun flow(file: PsiFile): Flow? {
        if (!isFlow(file)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return (toValue(root(file)) as? Map<String, Any?>)?.let(::Flow)
    }

    // The flow in a text that is not a PSI file yet, such as the current content of a document: a PSI that
    // is not committed may still describe an older text.
    fun parse(project: Project, source: String): Flow? =
        flow(PsiFileFactory.getInstance(project).createFileFromText("flow.yml", YAMLLanguage.INSTANCE, source))

    // The plugin type of the innermost mapping around the offset that declares one: a task, a trigger,
    // a condition or a task runner. Null outside of any.
    fun typeAt(file: PsiFile, offset: Int): String? {
        val element = file.findElementAt(offset)?.takeIf { it !is com.intellij.psi.PsiWhiteSpace }
            ?: file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return null
        var mapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java)
        while (mapping != null) {
            val type = mapping.getKeyValueByKey("type")?.valueText
            if (type != null && PLUGIN_TYPE.matches(type)) return type
            mapping = PsiTreeUtil.getParentOfType(mapping, YAMLMapping::class.java)
        }
        return null
    }

    private val PLUGIN_TYPE = Regex("""[A-Za-z_][\w]*(\.[\w$]+)+""")

    // Maps and sequences become Map/List, scalars the text they were written as: `id: 007` is the id "007", which
    // Kestra keeps as text, not the number 7. Only a default is typed, see toDefault.
    fun toValue(value: YAMLValue?): Any? = when (value) {
        is YAMLMapping -> value.keyValues.associate { it.keyText to if (it.keyText in DEFAULTS) toDefault(it.value) else toValue(it.value) }
        is YAMLSequence -> value.items.map { toValue(it.value) }
        is YAMLScalar -> value.textValue
        else -> null
    }

    // What an input is prefilled with. A scalar stays its text (`defaults: 0123` is the string "0123"), except a
    // plain null, which is no default. Inside a map or list the scalars take their YAML type, as the VS Code
    // extension sends them: a JSON default must not turn into strings.
    private fun toDefault(value: YAMLValue?): Any? = when (value) {
        is YAMLScalar -> value.textValue.takeUnless { isPlain(value) && yaml11(it) == null }
        else -> typed(value)
    }

    private fun typed(value: YAMLValue?): Any? = when (value) {
        is YAMLMapping -> value.keyValues.associate { it.keyText to typed(it.value) }
        is YAMLSequence -> value.items.map { typed(it.value) }
        is YAMLScalar -> if (isPlain(value)) yaml11(value.textValue) else value.textValue
        else -> null
    }

    private fun isPlain(scalar: YAMLScalar) = scalar !is YAMLQuotedText && scalar !is YAMLBlockScalar

    private val DEFAULTS = setOf("defaults", "prefill")

    // A plain scalar as the YAML 1.1 resolver of Kestra's parser (SnakeYAML) types it, so `yes` and `off` are
    // booleans, `0123` is octal and `~` is null (null here). What does not match stays text, and so does a number
    // that has no JSON form: `1e999` or `.inf` must not reach Gson, which refuses non-finite numbers.
    fun yaml11(text: String): Any? {
        if (text.isEmpty() || text in NULLS) return null
        if (text in TRUE) return true
        if (text in FALSE) return false
        INT.matchEntire(text)?.let { return integer(it) }
        if (FLOAT.matches(text)) return float(text)?.takeIf { it.isFinite() } ?: text
        return text
    }

    private val NULLS = setOf("~", "null", "Null", "NULL")
    private val TRUE = setOf("yes", "Yes", "YES", "true", "True", "TRUE", "on", "On", "ON")
    private val FALSE = setOf("no", "No", "NO", "false", "False", "FALSE", "off", "Off", "OFF")

    // The int forms of the 1.1 resolver: binary, octal, decimal, hexadecimal and base 60, `_` allowed in the digits.
    private val INT = Regex("([-+]?)(?:0b_*([01][01_]*)|0_*([0-7][0-7_]*)|(0|[1-9][0-9_]*)|0x_*([0-9a-fA-F][0-9a-fA-F_]*)|([1-9][0-9_]*(?::[0-5]?[0-9])+))")
    private val FLOAT = Regex(
        "[-+]?(?:[0-9][0-9_]*\\.[0-9_]*(?:[eE][-+]?[0-9]+)?|[0-9][0-9_]*[eE][-+]?[0-9]+|\\.[0-9_]+(?:[eE][-+]?[0-9]+)?" +
            "|[0-9][0-9_]*(?::[0-5]?[0-9])+\\.[0-9_]*|\\.(?:inf|Inf|INF))|\\.(?:nan|NaN|NAN)",
    )

    private fun integer(match: MatchResult): Any {
        val (sign, binary, octal, decimal, hex, sexagesimal) = match.destructured
        fun digits(text: String, radix: Int) = java.math.BigInteger(text.replace("_", ""), radix)
        val magnitude = when {
            binary.isNotEmpty() -> digits(binary, 2)
            octal.isNotEmpty() -> digits(octal, 8)
            decimal.isNotEmpty() -> digits(decimal, 10)
            hex.isNotEmpty() -> digits(hex, 16)
            else -> sexagesimal.replace("_", "").split(':').fold(java.math.BigInteger.ZERO) { sum, part -> sum * java.math.BigInteger.valueOf(60) + part.toBigInteger() }
        }
        val number = if (sign == "-") magnitude.negate() else magnitude
        return if (number.bitLength() < Long.SIZE_BITS) number.toLong() else number
    }

    // Null for the forms that cannot be a finite number.
    private fun float(text: String): Double? {
        val plain = text.replace("_", "")
        if (plain.contains("inf", ignoreCase = true) || plain.contains("nan", ignoreCase = true)) return null
        val sign = if (plain.startsWith("-")) -1.0 else 1.0
        val unsigned = plain.removePrefix("-").removePrefix("+")
        // Base 60: 1:30.5 is 90.5.
        if (unsigned.contains(':')) return sign * unsigned.split(':').fold(0.0) { sum, part -> sum * 60 + part.toDouble() }
        return plain.toDoubleOrNull()
    }

    // A validation path segment selecting a sequence item by its `id`, as Kestra 2.0 reports tasks[my-task].
    data class ById(val id: String)

    // The element a validation path such as tasks[0].type or tasks[my-task].message points to. When the
    // path does not fully resolve, the deepest item found: a task missing a property points to its `id`.
    fun find(file: PsiFile, path: List<Any>): PsiElement? {
        val root = (file as? YAMLFile)?.documents?.firstOrNull()?.topLevelValue ?: return null
        if (path.isEmpty()) {
            return null
        }
        val (element, complete) = walk(root, path)
        if (complete) {
            return element
        }
        // Kestra can report a nested task under the wrong parent id: resume from the deepest id segment.
        val last = path.indexOfLast { it is ById }
        val item = if (last > 0) findItemById(root, (path[last] as ById).id) else null
        return item?.let { walk(it, path.subList(last + 1, path.size), it).first } ?: element
    }

    // The deepest element reached, anchored when the path stops early, and whether the whole path resolved.
    private fun walk(start: PsiElement, path: List<Any>, initial: PsiElement? = null): Pair<PsiElement?, Boolean> {
        var current: YAMLValue? = when (start) {
            is YAMLSequenceItem -> start.value
            is YAMLValue -> start
            else -> null
        }
        var element: PsiElement? = initial
        for (segment in path) {
            val next: PsiElement? = when (segment) {
                is Int -> (current as? YAMLSequence)?.items?.getOrNull(segment)
                is ById -> (current as? YAMLSequence)?.items?.firstOrNull { (it.value as? YAMLMapping)?.getKeyValueByKey("id")?.valueText == segment.id }
                else -> (current as? YAMLMapping)?.getKeyValueByKey(segment.toString())
            }
            if (next == null) {
                return element?.let(::anchor) to false
            }
            element = next
            current = when (next) {
                is YAMLSequenceItem -> next.value
                is YAMLKeyValue -> next.value
                else -> null
            }
        }
        return element to true
    }

    // A sequence item is anchored on its `id:` line rather than its whole block.
    private fun anchor(element: PsiElement): PsiElement =
        ((element as? YAMLSequenceItem)?.value as? YAMLMapping)?.getKeyValueByKey("id") ?: element

    fun findItemById(root: PsiElement, id: String): YAMLSequenceItem? =
        PsiTreeUtil.findChildrenOfType(root, YAMLSequenceItem::class.java)
            .firstOrNull { (it.value as? YAMLMapping)?.getKeyValueByKey("id")?.valueText == id }

    fun parsePath(path: String): List<Any> = buildList {
        for (part in Regex("""([^.\[\]]+)|\[([^\]]+)]""").findAll(path)) {
            val name = part.groupValues[1]
            val index = part.groupValues[2]
            when {
                name.isNotEmpty() -> add(name)
                index.toIntOrNull() != null -> add(index.toInt())
                else -> add(ById(index))
            }
        }
    }
}
