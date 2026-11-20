package io.kestra.jetbrains.api

import com.google.gson.JsonElement

// One entry of the POST /flows/validate response.
data class ValidateResult(
    val constraints: List<String>,
    val warnings: List<String>,
    val deprecationPaths: List<String>,
) {
    companion object {
        fun parse(json: JsonElement?): List<ValidateResult> {
            if (json == null || !json.isJsonArray) {
                return emptyList()
            }
            return json.asJsonArray.filter { it.isJsonObject }.map { element ->
                val obj = element.asJsonObject
                fun strings(name: String) = obj.get(name)?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
                val constraints = obj.get("constraints")?.takeIf { it.isJsonPrimitive }?.asString
                ValidateResult(splitConstraints(constraints), strings("warnings"), strings("deprecationPaths"))
            }
        }

        fun splitConstraints(constraints: String?): List<String> =
            constraints?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }
}
