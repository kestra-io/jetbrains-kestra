package io.kestra.jetbrains.api

object KestraVersion {
    fun major(version: String?): Int? = version?.substringBefore('.')?.trim()?.toIntOrNull()
}
