package dev.uranium.launcher.core

import kotlinx.serialization.json.Json

/** Lenient reader for third-party metadata: upstream adds fields all the time. */
val LauncherJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

val PrettyJson: Json = Json(LauncherJson) { prettyPrint = true }
