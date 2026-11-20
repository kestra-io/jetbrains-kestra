package io.kestra.jetbrains.namespace

// Pure helpers for namespace paths and the excluded names, shared by uploads and the namespace browser.
object NamespacePaths {
    // Matches a file or folder name against one pattern. A leading and/or trailing "*" is a wildcard
    // (*.pem, .env.*, id_rsa*); anything else is an exact name. Case-insensitive, as in VS Code.
    fun matches(name: String, pattern: String): Boolean {
        val n = name.lowercase()
        val p = pattern.lowercase()
        return when {
            '*' !in p -> n == p
            p.length > 1 && p.startsWith('*') && p.endsWith('*') -> n.contains(p.substring(1, p.length - 1))
            p.startsWith('*') -> n.endsWith(p.substring(1))
            p.endsWith('*') -> n.startsWith(p.dropLast(1))
            else -> {
                val (prefix, suffix) = p.split('*', limit = 2)
                n.startsWith(prefix) && n.endsWith(suffix) && n.length >= prefix.length + suffix.length
            }
        }
    }

    fun isExcluded(name: String, patterns: List<String>) = patterns.any { matches(name, it) }

    // Returns the problem with a target path typed by the user, or null.
    fun validate(path: String, requireFileName: Boolean): String? {
        val trimmed = path.trim()
        return when {
            !trimmed.startsWith('/') -> "The path must start with /"
            requireFileName && (trimmed.endsWith('/') || trimmed == "/") -> "The path must include a file name"
            trimmed.split('/').any { it == ".." || it == "." } -> "The path cannot contain . or .."
            else -> null
        }
    }

    // "/base" + "dir/file" -> "/base/dir/file"
    fun join(base: String, relative: String): String = "/" + (base.trim().trim('/').split('/') + relative.split('/'))
        .filter { it.isNotEmpty() }.joinToString("/")

    fun name(path: String) = path.trimEnd('/').substringAfterLast('/')

    fun parent(path: String) = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
}
