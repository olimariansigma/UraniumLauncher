package dev.uranium.launcher.account

import dev.uranium.launcher.core.LauncherJson
import dev.uranium.launcher.net.Http
import dev.uranium.launcher.net.HttpResult
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A sign-in problem with a message that is safe and useful to show to the player. */
class AuthException(message: String) : Exception(message)

@Serializable
data class DeviceCode(
    @SerialName("user_code") val userCode: String,
    @SerialName("device_code") val deviceCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
)

data class MicrosoftTokens(val accessToken: String, val refreshToken: String)

/**
 * Microsoft account sign-in for Minecraft: Java Edition using the OAuth 2.0 device-code flow:
 * Microsoft token -> Xbox Live -> XSTS -> Minecraft services -> profile.
 *
 * Needs an Entra (Azure) application id that Mojang has approved for Minecraft services; see the
 * README for how to register one.
 */
class MicrosoftAuth(
    private val http: Http,
    private val clientId: () -> String,
    private val nowMillis: () -> Long,
) {
    val isConfigured: Boolean get() = clientId().isNotBlank()

    /** Step 1: ask Microsoft for a code the player types in at [DeviceCode.verificationUri]. */
    suspend fun requestDeviceCode(): DeviceCode {
        val id = requireClientId()
        val result = http.postForm("$AUTHORITY/devicecode", mapOf("client_id" to id, "scope" to SCOPE))
        if (!result.ok) throw AuthException(describeOAuthError(result, "Could not start Microsoft sign-in"))
        return LauncherJson.decodeFromString<DeviceCode>(result.body)
    }

    /** Step 2: poll until the player finishes signing in, declines, or the code expires. */
    suspend fun awaitDeviceCode(code: DeviceCode): MicrosoftTokens {
        var interval = code.interval.coerceAtLeast(1)
        val deadline = nowMillis() + code.expiresIn * 1000L
        while (nowMillis() < deadline) {
            delay(interval * 1000L)
            val result = http.postForm(
                "$AUTHORITY/token",
                mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to requireClientId(),
                    "device_code" to code.deviceCode,
                ),
            )
            if (result.ok) return parseTokens(result.body)
            when (errorCode(result)) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "authorization_declined" -> throw AuthException("Sign-in was cancelled in the browser.")
                "expired_token", "bad_verification_code" -> throw AuthException("The sign-in code expired. Please try again.")
                else -> throw AuthException(describeOAuthError(result, "Microsoft sign-in failed"))
            }
        }
        throw AuthException("The sign-in code expired. Please try again.")
    }

    suspend fun refresh(refreshToken: String): MicrosoftTokens {
        val result = http.postForm(
            "$AUTHORITY/token",
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to requireClientId(),
                "refresh_token" to refreshToken,
                "scope" to SCOPE,
            ),
        )
        if (!result.ok) throw AuthException("Your Microsoft session has expired. Please sign in again.")
        return parseTokens(result.body)
    }

    /** Steps 3-6: exchange a Microsoft token for a Minecraft session and profile. */
    suspend fun minecraftAccount(tokens: MicrosoftTokens): Account {
        val xbl = xboxRequest(
            "https://user.auth.xboxlive.com/user/authenticate",
            buildJsonObject {
                putJsonObject("Properties") {
                    put("AuthMethod", "RPS")
                    put("SiteName", "user.auth.xboxlive.com")
                    put("RpsTicket", "d=${tokens.accessToken}")
                }
                put("RelyingParty", "http://auth.xboxlive.com")
                put("TokenType", "JWT")
            },
        )
        val xblToken = xbl.string("Token") ?: throw AuthException("Xbox Live sign-in returned no token.")

        val xsts = xboxRequest(
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            buildJsonObject {
                putJsonObject("Properties") {
                    put("SandboxId", "RETAIL")
                    putJsonArray("UserTokens") { add(kotlinx.serialization.json.JsonPrimitive(xblToken)) }
                }
                put("RelyingParty", "rp://api.minecraftservices.com/")
                put("TokenType", "JWT")
            },
        )
        val xstsToken = xsts.string("Token") ?: throw AuthException("Xbox sign-in returned no token.")
        val claims = xsts["DisplayClaims"]?.jsonObject?.get("xui")?.jsonArray?.firstOrNull()?.jsonObject
        val userHash = claims?.string("uhs") ?: throw AuthException("Xbox sign-in returned no user hash.")

        val login = http.postJson(
            "https://api.minecraftservices.com/authentication/login_with_xbox",
            buildJsonObject { put("identityToken", "XBL3.0 x=$userHash;$xstsToken") }.toString(),
        )
        if (!login.ok) {
            throw AuthException(
                if (login.status == 403) {
                    "Minecraft services rejected this launcher's application id. A newly registered id " +
                        "must be approved by Mojang before it can sign players in (see the README)."
                } else {
                    "Minecraft sign-in failed (HTTP ${login.status})."
                }
            )
        }
        val session = LauncherJson.parseToJsonElement(login.body).jsonObject
        val mcToken = session.string("access_token") ?: throw AuthException("Minecraft sign-in returned no token.")
        val expiresIn = session["expires_in"]?.jsonPrimitive?.long ?: 86_400L

        val profile = http.getResult(
            "https://api.minecraftservices.com/minecraft/profile",
            mapOf("Authorization" to "Bearer $mcToken"),
        )
        if (profile.status == 404) {
            throw AuthException("This Microsoft account does not own Minecraft: Java Edition, or has not created a profile yet.")
        }
        if (!profile.ok) throw AuthException("Could not load the Minecraft profile (HTTP ${profile.status}).")
        val profileJson = LauncherJson.parseToJsonElement(profile.body).jsonObject

        return Account(
            kind = AccountKind.MICROSOFT,
            username = profileJson.string("name") ?: throw AuthException("The Minecraft profile has no name."),
            uuid = profileJson.string("id") ?: throw AuthException("The Minecraft profile has no id."),
            accessToken = mcToken,
            xuid = claims.string("xid").orEmpty(),
            refreshToken = tokens.refreshToken,
            // Renew a little early so a session never expires mid-launch.
            expiresAt = nowMillis() + (expiresIn - 300).coerceAtLeast(60) * 1000L,
        )
    }

    /** Returns [account] unchanged while its session is valid, otherwise a renewed copy. */
    suspend fun ensureFresh(account: Account): Account {
        if (!account.isOnline || nowMillis() < account.expiresAt) return account
        if (account.refreshToken.isBlank()) throw AuthException("Please sign in to ${account.username} again.")
        return minecraftAccount(refresh(account.refreshToken))
    }

    private suspend fun xboxRequest(url: String, body: JsonObject): JsonObject {
        val result = http.postJson(url, body.toString(), mapOf("x-xbl-contract-version" to "1"))
        val json = runCatching { LauncherJson.parseToJsonElement(result.body).jsonObject }.getOrNull()
        if (result.ok && json != null) return json
        val xErr = json?.get("XErr")?.jsonPrimitive?.content
        throw AuthException(xboxErrorMessage(xErr) ?: "Xbox Live sign-in failed (HTTP ${result.status}).")
    }

    private fun parseTokens(body: String): MicrosoftTokens {
        val json = LauncherJson.parseToJsonElement(body).jsonObject
        return MicrosoftTokens(
            accessToken = json.string("access_token") ?: throw AuthException("Microsoft returned no access token."),
            refreshToken = json.string("refresh_token").orEmpty(),
        )
    }

    private fun requireClientId(): String = clientId().trim().ifEmpty {
        throw AuthException("Microsoft sign-in is not configured: no application (client) id is set. See Settings.")
    }

    private fun errorCode(result: HttpResult): String? =
        runCatching { LauncherJson.parseToJsonElement(result.body).jsonObject.string("error") }.getOrNull()

    private fun describeOAuthError(result: HttpResult, prefix: String): String {
        val json = runCatching { LauncherJson.parseToJsonElement(result.body).jsonObject }.getOrNull()
        val detail = json?.string("error_description")?.lineSequence()?.firstOrNull() ?: json?.string("error")
        return if (detail != null) "$prefix: $detail" else "$prefix (HTTP ${result.status})."
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
        const val SCOPE = "XboxLive.signin offline_access"

        /** The XSTS errors players actually hit, in plain language. */
        fun xboxErrorMessage(xErr: String?): String? = when (xErr) {
            "2148916227" -> "This Xbox account is banned."
            "2148916233" -> "This Microsoft account has no Xbox profile yet. Sign in at xbox.com once to create one."
            "2148916235" -> "Xbox Live is not available in this account's country or region."
            "2148916236", "2148916237" -> "This account needs adult verification on the Xbox website before it can play."
            "2148916238" -> "This is a child account. An adult must add it to a Microsoft family before it can play."
            else -> null
        }
    }
}
