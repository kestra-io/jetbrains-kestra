package io.kestra.jetbrains.flow

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
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

    // Maps and sequences become Map/List, scalars their text value.
    fun toValue(value: YAMLValue?): Any? = when (value) {
        is YAMLMapping -> value.keyValues.associate { it.keyText to toValue(it.value) }
        is YAMLSequence -> value.items.map { toValue(it.value) }
        is YAMLScalar -> value.textValue
        else -> null
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
