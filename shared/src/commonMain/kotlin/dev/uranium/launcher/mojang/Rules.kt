package dev.uranium.launcher.mojang

import dev.uranium.launcher.core.Arch
import dev.uranium.launcher.core.OsType
import dev.uranium.launcher.core.PlatformServices

/** The host as Mojang's rule system sees it. */
data class RuleEnv(val osName: String, val arch: String, val osVersion: String) {
    companion object {
        fun of(platform: PlatformServices): RuleEnv = of(platform.os, platform.arch, platform.osVersion)

        fun of(os: OsType, arch: Arch, osVersion: String): RuleEnv = RuleEnv(os.mojangName, arch.mojangName, osVersion)
    }
}

/**
 * Mojang rule semantics: with no rules everything is allowed; otherwise the last matching rule
 * decides, and nothing matching means "disallow".
 */
fun rulesAllow(rules: List<Rule>?, env: RuleEnv, features: Map<String, Boolean> = emptyMap()): Boolean {
    if (rules.isNullOrEmpty()) return true
    var allowed = false
    for (rule in rules) {
        if (rule.matches(env, features)) allowed = rule.action == "allow"
    }
    return allowed
}

private fun Rule.matches(env: RuleEnv, features: Map<String, Boolean>): Boolean {
    os?.let { os ->
        if (os.name != null && os.name != env.osName) return false
        if (os.arch != null && os.arch != env.arch) return false
        if (os.version != null) {
            val matches = runCatching { Regex(os.version).containsMatchIn(env.osVersion) }.getOrDefault(false)
            if (!matches) return false
        }
    }
    this.features?.forEach { (name, wanted) ->
        if ((features[name] ?: false) != wanted) return false
    }
    return true
}
