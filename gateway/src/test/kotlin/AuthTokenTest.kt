import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Token verification, against tokens this test mints itself.
 *
 * This is the only code in the system that decides who is calling, and it is
 * hand-rolled rather than a JWT library — which is a defensible choice only if
 * the checks are actually pinned. Every assertion below is a way a real
 * deployment gets broken into: `alg: none`, algorithm confusion, a signature
 * from the wrong key, a payload edited after signing, a token minted by another
 * realm, a token minted for another client, an expired token replayed.
 *
 * A real RSA keypair is generated here and the realm's JWKS is served through
 * the `fetchJwks` seam the class already takes for exactly this purpose, so
 * nothing is stubbed out: the signature really is verified, or these tests do
 * not pass.
 */
class AuthTokenTest {

    // ---- the valid path -----------------------------------------------------

    @Test
    fun `a well-formed token names the caller, their id and their roles`() {
        val who = auth().verify(token(claims(
            roles = listOf("use-ui", "admin"),
            extra = mapOf("preferred_username" to "rock", "name" to "Rock de Vocht", "sid" to "sess-7"),
        )))

        assertEquals("rock", who.username)
        assertEquals("Rock de Vocht", who.name)
        assertEquals(SUBJECT, who.subject)
        assertEquals("sess-7", who.sessionId)
        assertEquals(setOf("use-ui", "admin"), who.roles)
        assertTrue(who.isAdmin)
        assertTrue(who.mayAudit)
    }

    @Test
    fun `a token with no username falls back to the subject, and the name to the username`() {
        // A service-account token has no preferred_username and no display
        // name. The audit trail still has to say something identifying, and
        // `sub` is the identifier that survives a rename anyway.
        val who = auth().verify(token(claims()))

        assertEquals(SUBJECT, who.username)
        assertEquals(SUBJECT, who.name)
        assertEquals("", who.sessionId, "no browser session means no sid — not a bug, a fact")
    }

    @Test
    fun `roles are read from realm_access and nowhere else`() {
        // A client role grants nothing here. Keycloak puts client roles under
        // resource_access, and reading those would mean a role assigned in
        // another client's scope silently became an admin role in this one.
        val who = auth().verify(token(claims(
            roles = listOf("use-ui"),
            extra = mapOf("resource_access" to JSONObject()
                .put("some-other-client", JSONObject().put("roles", JSONArray().put("admin")))),
        )))

        assertEquals(setOf("use-ui"), who.roles)
        assertTrue(!who.isAdmin)
    }

    @Test
    fun `a token with no roles at all is a principal with no capabilities`() {
        val who = auth().verify(token(claims(roles = null)))

        assertEquals(emptySet(), who.roles)
        assertTrue(!who.isAdmin && !who.mayAudit)
    }

    // ---- signature and algorithm -------------------------------------------

    @Test
    fun `alg none is refused — an unsigned token is not a credential`() {
        val e = assertFailsWith<Auth.Rejected> {
            auth().verify(token(claims(roles = listOf("admin")), alg = "none"))
        }
        assertTrue("algorithm" in e.message!!, e.message!!)
    }

    @Test
    fun `a symmetric algorithm is refused, whatever key it claims to use`() {
        // Algorithm confusion: HS256 with the realm's PUBLIC key as the HMAC
        // secret is the classic forgery, and the public key is published in the
        // JWKS for anyone to fetch. The only defence is to refuse the algorithm.
        val e = assertFailsWith<Auth.Rejected> {
            auth().verify(token(claims(roles = listOf("admin")), alg = "HS256"))
        }
        assertTrue("algorithm" in e.message!!, e.message!!)
    }

    @Test
    fun `a token signed by a different key is refused even under a known key id`() {
        val forged = token(claims(roles = listOf("admin")), keys = OTHER_KEYS)

        assertFailsWith<Auth.Rejected> { auth().verify(forged) }
    }

    @Test
    fun `a payload edited after signing is refused`() {
        val honest = token(claims(roles = listOf("use-ui")))
        val (header, _, signature) = honest.split('.')
        val promoted = claims(roles = listOf("use-ui", "admin"))
        val tampered = "$header.${b64(promoted.toString())}.$signature"

        val e = assertFailsWith<Auth.Rejected> { auth().verify(tampered) }
        assertTrue("signature" in e.message!!, e.message!!)
    }

    @Test
    fun `a token with no key id is refused rather than tried against every key`() {
        val e = assertFailsWith<Auth.Rejected> { auth().verify(token(claims(), kid = null)) }
        assertTrue("key id" in e.message!!, e.message!!)
    }

    @Test
    fun `an unknown key id refetches the JWKS once, then stops`() {
        // A realm key rotation has to land without a restart, so an unknown kid
        // triggers a refetch. That is also a free denial of service if it is
        // unbounded: a stream of tokens carrying invented key ids would have the
        // gateway hammering Keycloak. One fetch a minute, and the rest are
        // refused from the cache.
        var fetches = 0
        val auth = Auth(ISSUER, "", "") { fetches++; jwks() }

        assertFailsWith<Auth.Rejected> { auth.verify(token(claims(), kid = "invented-1")) }
        assertEquals(1, fetches)
        assertFailsWith<Auth.Rejected> { auth.verify(token(claims(), kid = "invented-2")) }
        assertEquals(1, fetches, "a second unknown key id must not cause a second fetch")

        // And the keys the fetch DID return are usable, from cache.
        assertEquals(SUBJECT, auth.verify(token(claims())).subject)
        assertEquals(1, fetches)
    }

    @Test
    fun `a JWKS that cannot be fetched refuses tokens rather than accepting them`() {
        val auth = Auth(ISSUER, "", "") { throw java.io.IOException("connection refused") }

        assertFailsWith<Auth.Rejected> { auth.verify(token(claims())) }
    }

    // ---- issuer and audience ------------------------------------------------

    @Test
    fun `a token from another realm is refused`() {
        val e = assertFailsWith<Auth.Rejected> {
            auth().verify(token(claims(iss = "https://elsewhere.example/realms/seshat")))
        }
        assertTrue(ISSUER in e.message!!, e.message!!)
    }

    @Test
    fun `a trailing slash on the issuer is not a different issuer`() {
        // Keycloak's issuer claim and the configured URL differ by a trailing
        // slash often enough that treating them as different issuers would mean
        // nobody could sign in, for a reason no log line would explain.
        assertEquals(SUBJECT, auth().verify(token(claims(iss = "$ISSUER/"))).subject)
    }

    @Test
    fun `the audience is accepted from aud as a string, from aud as an array, or from azp`() {
        val auth = auth(audience = "seshat-ui")

        assertEquals(SUBJECT, auth.verify(token(claims(extra = mapOf("aud" to "seshat-ui")))).subject)
        assertEquals(SUBJECT, auth.verify(token(claims(
            extra = mapOf("aud" to JSONArray().put("account").put("seshat-ui")),
        ))).subject)
        // A public client's token often carries only azp — which is why it
        // counts as an audience here.
        assertEquals(SUBJECT, auth.verify(token(claims(extra = mapOf("azp" to "seshat-ui")))).subject)
    }

    @Test
    fun `a token minted for another client is refused when an audience is configured`() {
        val e = assertFailsWith<Auth.Rejected> {
            auth(audience = "seshat-ui").verify(token(claims(
                extra = mapOf("aud" to "grafana", "azp" to "grafana"),
            )))
        }
        assertTrue("this application" in e.message!!, e.message!!)
    }

    @Test
    fun `with no audience configured any token the realm issued is accepted`() {
        // Documented rather than desirable: this is why compose sets
        // KEYCLOAK_AUDIENCE.
        assertEquals(SUBJECT, auth().verify(token(claims(extra = mapOf("aud" to "grafana")))).subject)
    }

    // ---- validity window ----------------------------------------------------

    @Test
    fun `an expired token is refused`() {
        val e = assertFailsWith<Auth.Rejected> { auth().verify(token(claims(exp = now() - 120))) }
        assertTrue("expired" in e.message!!, e.message!!)
    }

    @Test
    fun `a token with no exp at all is refused`() {
        // optLong("exp", 0) — a token that simply omits its expiry must not be
        // read as one that never expires.
        assertFailsWith<Auth.Rejected> { auth().verify(token(claims(exp = null))) }
    }

    @Test
    fun `a few seconds of clock skew either side is tolerated`() {
        // The gateway and Keycloak are separate containers; a second or two of
        // drift between them must not log everyone out.
        assertEquals(SUBJECT, auth().verify(token(claims(exp = now() - 5))).subject)
        assertEquals(SUBJECT, auth().verify(token(claims(
            extra = mapOf("nbf" to now() + 5),
        ))).subject)
    }

    @Test
    fun `a token that is not valid yet is refused`() {
        val e = assertFailsWith<Auth.Rejected> {
            auth().verify(token(claims(extra = mapOf("nbf" to now() + 600))))
        }
        assertTrue("not valid yet" in e.message!!, e.message!!)
    }

    // ---- malformed input ----------------------------------------------------

    @Test
    fun `anything that is not three base64 segments is refused, not parsed`() {
        val auth = auth()
        for (bad in listOf(
            "",
            "not-a-token",
            "only.two",
            "a.b.c.d",
            "%%%.%%%.%%%",
            "eyJ9.eyJ9.sig",           // valid base64, invalid JSON
        )) {
            assertFailsWith<Auth.Rejected>("'$bad' should have been refused") { auth.verify(bad) }
        }
    }

    // ---- the JWKS document itself ------------------------------------------

    @Test
    fun `both of a realm's signing keys are read`() {
        val keys = Auth.parseJwks(JSONObject().put("keys", JSONArray()
            .put(jwk("rsa-1", public(REALM_KEYS)))
            .put(jwk("rsa-2", public(OTHER_KEYS)))).toString())

        assertEquals(setOf("rsa-1", "rsa-2"), keys.keys)
    }

    @Test
    fun `an encryption key is skipped, so it can never verify a signature`() {
        // Keycloak publishes its RSA-OAEP encryption key in the same document.
        // Using it to check a signature is a category error, and a quiet one.
        val keys = Auth.parseJwks(JSONObject().put("keys", JSONArray()
            .put(jwk("rsa-enc", public(REALM_KEYS), use = "enc"))
            .put(jwk("rsa-sig", public(REALM_KEYS)))).toString())

        assertEquals(setOf("rsa-sig"), keys.keys)
    }

    @Test
    fun `a non-RSA key and a key with no id are skipped rather than throwing`() {
        val keys = Auth.parseJwks(JSONObject().put("keys", JSONArray()
            .put(JSONObject().put("kty", "EC").put("kid", "ec-1").put("crv", "P-256"))
            .put(jwk("", public(REALM_KEYS)))
            .put(jwk("rsa-1", public(REALM_KEYS)))).toString())

        assertEquals(setOf("rsa-1"), keys.keys)
    }

    @Test
    fun `a JWKS with no keys array is empty, not an error`() {
        assertEquals(emptyMap(), Auth.parseJwks("{}"))
    }

    // ---- minting -----------------------------------------------------------

    private companion object {
        const val ISSUER = "https://home.peter.nz/seshat/auth/realms/seshat"
        const val SUBJECT = "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"
        const val KID = "rsa-1"

        /** Two keypairs: the realm's, and one an attacker controls. Generated
         *  once — RSA key generation is the slowest thing in this file. */
        val REALM_KEYS: KeyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }.generateKeyPair()
        val OTHER_KEYS: KeyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }.generateKeyPair()

        val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        fun now() = System.currentTimeMillis() / 1000

        fun public(pair: KeyPair) = pair.public as RSAPublicKey

        fun b64(bytes: ByteArray): String = B64.encodeToString(bytes)
        fun b64(text: String): String = b64(text.toByteArray(Charsets.UTF_8))

        fun jwk(kid: String, key: RSAPublicKey, use: String? = "sig"): JSONObject =
            JSONObject()
                .put("kty", "RSA")
                .put("alg", "RS256")
                .put("kid", kid)
                .put("n", b64(key.modulus.toByteArray()))
                .put("e", b64(key.publicExponent.toByteArray()))
                .also { if (use != null) it.put("use", use) }

        /** The realm's JWKS document, as Keycloak publishes it. */
        fun jwks(): String =
            JSONObject().put("keys", JSONArray().put(jwk(KID, public(REALM_KEYS)))).toString()

        fun auth(audience: String = "") = Auth(ISSUER, "", audience) { jwks() }

        /** A Keycloak access-token payload. `exp = null` omits the claim. */
        fun claims(
            iss: String = ISSUER,
            exp: Long? = now() + 300,
            roles: List<String>? = listOf("use-ui"),
            extra: Map<String, Any> = emptyMap(),
        ): JSONObject = JSONObject().apply {
            put("iss", iss)
            put("sub", SUBJECT)
            put("iat", now())
            if (exp != null) put("exp", exp)
            if (roles != null) {
                put("realm_access", JSONObject().put("roles", JSONArray(roles)))
            }
            for ((k, v) in extra) put(k, v)
        }

        /**
         * A signed JWT. `alg` other than RS256 produces a token with an empty
         * signature, which is what an `alg: none` forgery looks like on the
         * wire — the point being that it is refused before the signature is
         * ever considered.
         */
        fun token(
            payload: JSONObject,
            keys: KeyPair = REALM_KEYS,
            kid: String? = KID,
            alg: String = "RS256",
        ): String {
            val header = JSONObject().put("typ", "JWT").put("alg", alg)
            if (kid != null) header.put("kid", kid)
            val signing = "${b64(header.toString())}.${b64(payload.toString())}"
            val signature = if (alg == "RS256") {
                Signature.getInstance("SHA256withRSA").run {
                    initSign(keys.private)
                    update(signing.toByteArray(Charsets.US_ASCII))
                    sign()
                }
            } else {
                ByteArray(0)
            }
            return "$signing.${b64(signature)}"
        }
    }
}
