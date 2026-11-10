package dev.uranium.launcher.account

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.core.PrettyJson
import dev.uranium.launcher.core.readTextOrNull
import dev.uranium.launcher.core.writeTextAtomic
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path

@Serializable
enum class AccountKind { OFFLINE, MICROSOFT }

@Serializable
data class Account(
    val kind: AccountKind,
    val username: String,
    /** Undashed hex, as the game expects for `--uuid`. */
    val uuid: String,
    /** Minecraft services token; "0" for offline accounts. */
    val accessToken: String = "0",
    val xuid: String = "",
    /** Microsoft OAuth refresh token, used to renew [accessToken] without asking the user again. */
    val refreshToken: String = "",
    /** Epoch millis after which [accessToken] must be renewed; 0 for offline accounts. */
    val expiresAt: Long = 0,
) {
    val id: String get() = "${kind.name.lowercase()}:$uuid"
    val userType: String get() = if (kind == AccountKind.MICROSOFT) "msa" else "legacy"
    val isOnline: Boolean get() = kind == AccountKind.MICROSOFT

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

@Serializable
data class AccountsState(val accounts: List<Account> = emptyList(), val selectedId: String? = null) {
    val selected: Account? get() = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()

    fun with(account: Account, select: Boolean = true): AccountsState {
        val others = accounts.filterNot { it.id == account.id }
        return AccountsState(others + account, if (select) account.id else selectedId)
    }

    fun without(id: String): AccountsState {
        val remaining = accounts.filterNot { it.id == id }
        return AccountsState(remaining, selectedId.takeIf { it != id } ?: remaining.firstOrNull()?.id)
    }
}

/**
 * Accounts live in `accounts.json` inside the launcher's data folder. Tokens are stored as-is,
 * like other launchers do; anyone who can read that folder can already read the game's own token.
 */
class AccountStore(private val file: Path, private val fs: FileSystem = FileSystem.SYSTEM) {
    fun load(): AccountsState = fs.readTextOrNull(file)
        ?.let { runCatching { LauncherJson.decodeFromString<AccountsState>(it) }.getOrNull() }
        ?: AccountsState()

    fun save(state: AccountsState) =
        fs.writeTextAtomic(file, PrettyJson.encodeToString(AccountsState.serializer(), state))
}
