import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64

/**
 * Who is calling, per a verified token.
 *
 * [username] is what a reader recognises and [subject] is what survives a
 * rename — the audit trail keeps both, because a row that says only "rock" is
 * ambiguous the day someone changes their username, and a row that says only
 * `f81d…` is unreadable.
 *
 * Capabilities are named, not spelled out at each call site. Two of them:
 * [isAdmin] may change the corpus, [mayAudit] may read what everyone did. They
 * come apart deliberately — `admin-observability` alone is an auditor who
 * cannot re-index, which is an assignment in Keycloak rather than a change
 * here.
 */
data class Principal(
    val username: String,
    val name: String,
    val roles: Set<String>,
    /** The token's `sub`: stable across a username change. */
    val subject: String = "",
    /** The token's `sid`: every action in one browser session shares it. */
    val sessionId: String = "",
) {
    val isAdmin get() = ADMIN in roles

    /** May read the audit trail, the consolidated logs and the metrics. */
    val mayAudit get() = ADMIN in roles || OBSERVABILITY in roles

    companion object {
        const val ADMIN = "admin"
        const val OBSERVABILITY = "admin-observability"
        const val USE_UI = "use-ui"
    }
}

/**
 * Keycloak bearer-token verification.
 *
 * A Keycloak access token is a standard RS256 JWT, so the JDK's RSA and SHA-256
 * primitives plus the JSON parser already in this jar are the entire
 * dependency list — no JWT library, no OIDC client.
 *
 * Verified, in order: the algorithm is RS256 (never `none`, never a symmetric
 * algorithm an attacker could supply the key for), the signature matches a key
 * from the realm's JWKS, the issuer is exactly ours, the audience contains our
 * client id when one is configured, and the token is inside its validity
 * window. Keys are fetched lazily and cached; an unknown key id triggers one
 * rate-limited refetch, which is how a realm key rotation lands without a
 * restart.
 *
 * The ISSUER is the browser-facing URL (through the proxy) because that is what
 * Keycloak stamps into the token, while the JWKS URL is the internal service
 * address this container can actually reach. They are separate settings for
 * exactly that reason.
 */
class Auth(
    private val issuer: String,
    jwksUrl: String,
    private val audience: String = "",
    private val fetchJwks: () -> String = defaultFetcher(jwksUrl),
) {
    private val log = LoggerFactory.getLogger("Auth")

    /** The token is missing, malformed, expired, forged, or from elsewhere. */
    class Rejected(message: String) : Exception(message)

    @Volatile private var keys: Map<String, PublicKey> = emptyMap()
    @Volatile private var lastFetch = 0L

    /** @throws Rejected with a reason safe to show the caller. */
    fun verify(token: String): Principal {
        val parts = token.split('.')
        if (parts.size != 3) throw Rejected("malformed token")

        val header = decode(parts[0])
        val payload = decode(parts[1])

        if (header.optString("alg") != "RS256") {
            throw Rejected("unsupported token algorithm '${header.optString("alg")}'")
        }
        val kid = header.optString("kid").ifBlank { throw Rejected("token has no key id") }
        val key = keyFor(kid) ?: throw Rejected("token signed with an unknown key — sign in again")

        val signed = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        val valid = Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update(signed); verify(base64(parts[2]))
        }
        if (!valid) throw Rejected("invalid token signature")

        if (payload.optString("iss").trimEnd('/') != issuer) throw Rejected("token is not from $issuer")

        // Audience, when configured. Without it any token this realm issued is
        // accepted — including one minted for a different client, which is why
        // compose sets it.
        if (audience.isNotBlank() && audience !in audiences(payload)) {
            throw Rejected("token was not issued for this application")
        }

        val now = System.currentTimeMillis() / 1000
        if (payload.optLong("exp", 0) < now - SKEW) throw Rejected("token has expired — sign in again")
        if (payload.has("nbf") && payload.optLong("nbf") > now + SKEW) throw Rejected("token is not valid yet")

        val roles = payload.optJSONObject("realm_access")?.optJSONArray("roles")
            ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
            ?: emptySet()

        val username = payload.optString("preferred_username").ifBlank { payload.optString("sub") }
        return Principal(
            username = username,
            name = payload.optString("name").ifBlank { username },
            roles = roles,
            subject = payload.optString("sub"),
            // `sid` is present on a token minted from a browser session and
            // absent on one minted for a service account — so it is optional
            // here, and an empty session id in the audit trail means exactly
            // that rather than a bug.
            sessionId = payload.optString("sid"),
        )
    }

    /** `aud` is a string or an array of strings; `azp` (authorised party) is
     *  what Keycloak sets for a public client whose token has no other
     *  audience, so both count. */
    private fun audiences(payload: JSONObject): Set<String> = buildSet {
        payload.optJSONArray("aud")?.let { arr ->
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
        payload.optString("aud").takeIf { it.isNotBlank() }?.let { add(it) }
        payload.optString("azp").takeIf { it.isNotBlank() }?.let { add(it) }
    }

    private fun keyFor(kid: String): PublicKey? {
        keys[kid]?.let { return it }
        synchronized(this) {
            keys[kid]?.let { return it }
            val now = System.currentTimeMillis()
            if (now - lastFetch < MIN_REFRESH_MS) return null
            lastFetch = now
            keys = try {
                parseJwks(fetchJwks())
            } catch (e: Exception) {
                log.warn("JWKS fetch failed: {}", e.toString())
                return null
            }
        }
        return keys[kid]
    }

    private fun decode(b64: String): JSONObject = try {
        JSONObject(String(base64(b64), Charsets.UTF_8))
    } catch (e: Exception) {
        throw Rejected("malformed token")
    }

    companion object {
        private const val SKEW = 30L
        private const val MIN_REFRESH_MS = 60_000L

        private fun base64(s: String): ByteArray = try {
            Base64.getUrlDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw Rejected("malformed token")
        }

        /** RSA signing keys out of a standard JWKS document, by key id. */
        fun parseJwks(json: String): Map<String, PublicKey> {
            val factory = KeyFactory.getInstance("RSA")
            val arr = JSONObject(json).optJSONArray("keys") ?: return emptyMap()
            return buildMap {
                for (i in 0 until arr.length()) {
                    val k = arr.getJSONObject(i)
                    if (k.optString("kty") != "RSA") continue
                    if (k.has("use") && k.optString("use") != "sig") continue
                    val kid = k.optString("kid").ifBlank { continue }
                    val n = BigInteger(1, Base64.getUrlDecoder().decode(k.getString("n")))
                    val e = BigInteger(1, Base64.getUrlDecoder().decode(k.getString("e")))
                    put(kid, factory.generatePublic(RSAPublicKeySpec(n, e)))
                }
            }
        }

        private fun defaultFetcher(jwksUrl: String): () -> String {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            return {
                val req = HttpRequest.newBuilder(URI.create(jwksUrl))
                    .timeout(Duration.ofSeconds(10)).GET().build()
                val res = client.send(req, HttpResponse.BodyHandlers.ofString())
                check(res.statusCode() == 200) { "JWKS endpoint returned HTTP ${res.statusCode()}" }
                res.body()
            }
        }
    }
}
