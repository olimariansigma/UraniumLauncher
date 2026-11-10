package dev.uranium.launcher.mojang

/** `group:artifact:version[:classifier][@ext]` as used by every Minecraft version descriptor. */
data class MavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String? = null,
    val extension: String = "jar",
) {
    /** Identity for de-duplication: the same module in two versions must not both be on the classpath. */
    val key: String get() = "$group:$artifact${classifier?.let { ":$it" } ?: ""}"

    val path: String
        get() {
            val file = buildString {
                append(artifact).append('-').append(version)
                if (classifier != null) append('-').append(classifier)
                append('.').append(extension)
            }
            return "${group.replace('.', '/')}/$artifact/$version/$file"
        }

    fun withClassifier(classifier: String): MavenCoordinate = copy(classifier = classifier)

    companion object {
        fun parse(name: String): MavenCoordinate {
            val ext = name.substringAfterLast('@', "jar")
            val parts = name.substringBeforeLast('@').split(':')
            require(parts.size >= 3) { "Not a Maven coordinate: $name" }
            return MavenCoordinate(parts[0], parts[1], parts[2], parts.getOrNull(3), ext)
        }
    }
}
