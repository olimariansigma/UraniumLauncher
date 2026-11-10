package dev.uranium.launcher.account

import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

@Serializable
enum class AccountKind { OFFLINE, MICROSOFT }

@Serializable
data class Account(
    val kind: AccountKind,
    val username: String,
    /** Undashed hex, as the game expects for `--uuid`. */
    val uuid: String,
    val accessToken: String = "0",
    val xuid: String = "",
) {
    val userType: String get() = if (kind == AccountKind.MICROSOFT) "msa" else "legacy"

    companion object {
        /**
         * Same derivation the vanilla server uses for offline players (a version-3 UUID of
         * "OfflinePlayer:<name>"), so skins-by-UUID and LAN worlds stay consistent across launchers.
         */
        fun offline(username: String): Account {
            val hash = "OfflinePlayer:$username".encodeUtf8().md5().toByteArray()
            hash[6] = ((hash[6].toInt() and 0x0f) or 0x30).toByte()
            hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = hash.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return Account(AccountKind.OFFLINE, username, hex)
        }
    }
}
