@file:Suppress("ExtractKtorModule")

package id.walt.issuer.issuance

import id.walt.commons.config.ConfigManager
import id.walt.credentials.issuance.Issuer.mergingJwtIssue
import id.walt.credentials.issuance.Issuer.mergingSdJwtIssue
import id.walt.credentials.vc.vcs.W3CVC
import id.walt.crypto.keys.Key
import id.walt.crypto.keys.KeySerialization
import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import id.walt.did.dids.DidService
import id.walt.did.dids.DidUtils
import id.walt.issuer.config.CredentialTypeConfig
import id.walt.issuer.config.OIDCIssuerServiceConfig
import id.walt.oid4vc.data.CredentialFormat
import id.walt.oid4vc.data.CredentialSupported
import id.walt.oid4vc.definitions.JWTClaims
import id.walt.oid4vc.errors.CredentialError
import id.walt.oid4vc.errors.DeferredCredentialError
import id.walt.oid4vc.interfaces.CredentialResult
import id.walt.oid4vc.providers.CredentialIssuerConfig
import id.walt.oid4vc.providers.IssuanceSession
import id.walt.oid4vc.providers.OpenIDCredentialIssuer
import id.walt.oid4vc.providers.TokenTarget
import id.walt.oid4vc.requests.BatchCredentialRequest
import id.walt.oid4vc.requests.CredentialRequest
import id.walt.oid4vc.responses.BatchCredentialResponse
import id.walt.oid4vc.responses.CredentialErrorCode
import id.walt.oid4vc.responses.CredentialResponse
import id.walt.oid4vc.util.randomUUID
import id.walt.sdjwt.SDMap
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.minutes

val supportedCredentialTypes = ConfigManager.getConfig<CredentialTypeConfig>().supportedCredentialTypes

/**
 * OIDC for Verifiable Credential Issuance service provider, implementing abstract service provider from OIDC4VC library.
 */
open class CIProvider : OpenIDCredentialIssuer(
    baseUrl = let {
        ConfigManager.getConfig<OIDCIssuerServiceConfig>().baseUrl
    }, config = CredentialIssuerConfig(credentialConfigurationsSupported = supportedCredentialTypes.flatMap { entry ->
        CredentialFormat.entries.map { format ->
            CredentialSupported(
                id = "${entry.key}_${format.value}",
                format = format,
                cryptographicBindingMethodsSupported = setOf("did"),
                cryptographicSuitesSupported = setOf("EdDSA", "ES256", "ES256K", "RSA"),
                types = entry.value
            )
        }
    }.associateBy { it.id })
) {
    private val log = KotlinLogging.logger { }

    companion object {

        val exampleIssuerKey by lazy { runBlocking { JWKKey.generate(KeyType.Ed25519) } }
        val exampleIssuerDid by lazy { runBlocking { DidService.registerByKey("jwk", exampleIssuerKey).did } }


        private val CI_TOKEN_KEY by lazy { runBlocking { JWKKey.generate(KeyType.Ed25519) } }
    }

    // -------------------------------
    // Simple in-memory session management
    private val authSessions: MutableMap<String, IssuanceSession> = mutableMapOf()


    var deferIssuance = false
    val deferredCredentialRequests = mutableMapOf<String, CredentialRequest>()
    override fun getSession(id: String): IssuanceSession? {
        log.debug { "RETRIEVING CI AUTH SESSION: $id" }
        return authSessions[id]
    }

    override fun getSessionByIdTokenRequestState(idTokenRequestState: String): IssuanceSession? {
        log.debug { "RETRIEVING CI AUTH SESSION by idTokenRequestState: $idTokenRequestState" }
        var properSession: IssuanceSession? = null
        authSessions.forEach { entry ->
            print("${entry.key} : ${entry.value}")
            val session = entry.value
            if (session.idTokenRequestState == idTokenRequestState) {
                properSession = session
            }
        }
        return properSession
    }


    override fun putSession(id: String, session: IssuanceSession): IssuanceSession? {
        log.debug { "SETTING CI AUTH SESSION: $id = $session" }
        return authSessions.put(id, session)
    }

    override fun removeSession(id: String): IssuanceSession? {
        log.debug { "REMOVING CI AUTH SESSION: $id" }
        return authSessions.remove(id)
    }


    // ------------------------------------------
    // Simple cryptographics operation interface implementations
    override fun signToken(
        target: TokenTarget,
        payload: JsonObject,
        header: JsonObject?,
        keyId: String?,
        privKey: Key?,
    ) =
        runBlocking {
            log.debug { "Signing JWS:   $payload" }
            log.debug { "JWS Signature: target: $target, keyId: $keyId, header: $header" }
            if (header != null && keyId != null && privKey != null) {
                val headers = mapOf("alg" to "ES256", "type" to "jwt", "kid" to keyId)
                privKey.signJws(payload.toString().toByteArray(), headers).also {
                    log.debug { "Signed JWS: >> $it" }
                }

            } else {
                CI_TOKEN_KEY.signJws(payload.toString().toByteArray()).also {
                    log.debug { "Signed JWS: >> $it" }
                }
            }
        }

    @OptIn(ExperimentalEncodingApi::class)
    override fun verifyTokenSignature(target: TokenTarget, token: String) = runBlocking {
        log.debug { "Verifying JWS: $token" }
        log.debug { "JWS Verification: target: $target" }

        val tokenHeader = Json.parseToJsonElement(Base64.decode(token.split(".")[0]).decodeToString()).jsonObject
        if (tokenHeader["kid"] != null) {
            val did = tokenHeader["kid"]!!.jsonPrimitive.content.split("#")[0]
            log.debug { "Resolving DID: $did" }
            val key = DidService.resolveToKey(did).getOrThrow()
            key.verifyJws(token).also { log.debug { "VERIFICATION IS: $it" } }
        } else {
            CI_TOKEN_KEY.verifyJws(token)
        }
    }.isSuccess

    // -------------------------------------
    // Implementation of abstract issuer service provider interface
    override fun generateCredential(credentialRequest: CredentialRequest): CredentialResult {
        log.debug { "GENERATING CREDENTIAL:" }
        log.debug { "Credential request: $credentialRequest" }
        log.debug { "CREDENTIAL REQUEST JSON -------:" }
        log.debug { Json.encodeToString(credentialRequest) }

        val (subjectDid, nonce) = parseFromJwt(
            credentialRequest.proof?.jwt ?: throw IllegalArgumentException("No proof.jwt in credential request!")
        )

        if (deferIssuance) return CredentialResult(credentialRequest.format, null, randomUUID()).also {
            deferredCredentialRequests[it.credentialId!!] = credentialRequest
        }
        return doGenerateCredential(credentialRequest, subjectDid, nonce)/*.also {
            // for testing purposes: defer next credential if multiple credentials are issued
            deferIssuance = !deferIssuance
        }*/
    }

    override fun getDeferredCredential(credentialID: String): CredentialResult {
        if (deferredCredentialRequests.containsKey(credentialID)) {
            return doGenerateCredential(
                deferredCredentialRequests[credentialID]!!, null, null
            ) // TODO: the null parameters
        }
        throw DeferredCredentialError(CredentialErrorCode.invalid_request, message = "Invalid credential ID given")
    }

    private fun doGenerateCredential(
        credentialRequest: CredentialRequest, subjectDid: String?, nonce: String?,
    ): CredentialResult {
        if (credentialRequest.format == CredentialFormat.mso_mdoc) throw CredentialError(
            credentialRequest, CredentialErrorCode.unsupported_credential_format
        )

        /*val types = credentialRequest.types ?: credentialRequest.credentialDefinition?.types ?: throw CredentialError(
            credentialRequest, CredentialErrorCode.unsupported_credential_type
        )
        val proofHeader = credentialRequest.proof?.jwt?.let { parseTokenHeader(it) } ?: throw CredentialError(
            credentialRequest, CredentialErrorCode.invalid_or_missing_proof, message = "Proof must be JWT proof"
        )

        val holderKid = proofHeader[JWTClaims.Header.keyID]?.jsonPrimitive?.content ?: throw CredentialError(
            credentialRequest,
            CredentialErrorCode.invalid_or_missing_proof,
            message = "Proof JWT header must contain kid claim"
        )*/

        val proofPayload = credentialRequest.proof?.jwt?.let { parseTokenPayload(it) } ?: throw CredentialError(
            credentialRequest, CredentialErrorCode.invalid_or_missing_proof, message = "Proof must be JWT proof"
        )
        val proofHeader = credentialRequest.proof?.jwt?.let { parseTokenHeader(it) } ?: throw CredentialError(
            credentialRequest, CredentialErrorCode.invalid_or_missing_proof, message = "Proof must be JWT proof"
        )

        val holderKid = proofHeader[JWTClaims.Header.keyID]?.jsonPrimitive?.content ?: throw CredentialError(
            credentialRequest,
            CredentialErrorCode.invalid_or_missing_proof,
            message = "Proof JWT header must contain kid claim"
        )
        val holderDid = if (DidUtils.isDidUrl(holderKid)) holderKid.substringBefore("#") else holderKid
        //val vc = W3CVC(universityDegreeCredentialExample.toList().associate { it.first to it.second.toJsonElement() })

        val data: IssuanceSessionData = (if (subjectDid == null || nonce == null) {
            repeat(10) {
                log.debug { "WARNING: RETURNING DEMO/EXAMPLE (= BOGUS) CREDENTIAL: subjectDid or nonce is null (was deferred issuance tried?)" }
            }
            listOf(
                IssuanceSessionData(
                    exampleIssuerKey,
                    exampleIssuerDid,
                    IssuanceRequest(
                        Json.parseToJsonElement(KeySerialization.serializeKey(exampleIssuerKey)).jsonObject,
                        exampleIssuerDid,
                        "OpenBadgeCredential_${credentialRequest.format.value}",
                        W3CVC.fromJson(IssuanceExamples.openBadgeCredentialData)
                    )
                )
            )
        } else {
            log.debug { "RETRIEVING VC FROM TOKEN MAPPING: $nonce" }
            tokenCredentialMapping[nonce]
                ?: throw IllegalArgumentException("The issuanceIdCredentialMapping does not contain a mapping for: $nonce!")
        }).first()

        return CredentialResult(format = credentialRequest.format, credential = JsonPrimitive(runBlocking {
            val vc = data.request.credentialData

            data.run {
                var issuerKid = issuerDid
                if (issuerDid.startsWith("did:key") && issuerDid.length == 186) // EBSI conformance corner case when issuer uses did:key instead of did:ebsi and no trust framework is defined
                    issuerKid = issuerDid + "#" + issuerDid.removePrefix("did:key:")

                if (issuerDid.startsWith("did:ebsi"))
                    issuerKid = issuerDid + "#" + issuerKey.getKeyId()

                when (credentialRequest.format) {
                    CredentialFormat.sd_jwt_vc -> vc.mergingSdJwtIssue(
                        issuerKey = issuerKey,
                        issuerDid = issuerDid,
                        subjectDid = holderDid,
                        mappings = request.mapping ?: JsonObject(emptyMap()),
                        additionalJwtHeader = emptyMap(),
                        additionalJwtOptions = emptyMap(),
                        disclosureMap = data.request.selectiveDisclosure ?: SDMap.Companion.generateSDMap(
                            JsonObject(emptyMap()),
                            JsonObject(emptyMap())
                        )
                    )

                    else -> vc.mergingJwtIssue(
                        issuerKey = issuerKey,
                        issuerDid = issuerDid,
                        issuerKid = issuerKid,
                        subjectDid = holderDid,
                        mappings = request.mapping ?: JsonObject(emptyMap()),
                        additionalJwtHeader = emptyMap(),
                        additionalJwtOptions = emptyMap(),
                    )
                }
            }.also { log.debug { "Respond VC: $it" } }
        }))
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun parseFromJwt(jwt: String): Pair<String, String> {
        val jwtParts = jwt.split(".")

        fun decodeJwtPart(idx: Int) =
            Json.parseToJsonElement(Base64.decode(jwtParts[idx]).decodeToString()).jsonObject

        val header = decodeJwtPart(0)
        val payload = decodeJwtPart(1)

        val subjectDid =
            header["kid"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("No kid in proof.jwt header!")
        val nonce = payload["nonce"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("No nonce in proof.jwt payload!")

        return Pair(subjectDid, nonce)
    }

    override fun generateBatchCredentialResponse(
        batchCredentialRequest: BatchCredentialRequest,
        accessToken: String,
    ): BatchCredentialResponse {
        val credentialRequestFormats = batchCredentialRequest.credentialRequests
                .map { it.format }

        require(credentialRequestFormats.distinct().size < 2) { "Credential requests don't have the same format: ${credentialRequestFormats.joinToString { it.value }}" }

        val keyIdsDistinct = batchCredentialRequest.credentialRequests.map { credReq ->
            credReq.proof?.jwt?.let { jwt -> parseTokenHeader(jwt) }
                ?.get(JWTClaims.Header.keyID)
                ?.jsonPrimitive?.content
                ?: throw CredentialError(
                    credReq,
                    CredentialErrorCode.invalid_or_missing_proof,
                    message = "Proof must be JWT proof"
                )
        }.distinct()

        require(keyIdsDistinct.size < 2) { "More than one key id requested" }

//        val keyId = keyIdsDistinct.first()


        batchCredentialRequest.credentialRequests.first().let { credentialRequest ->

            val (subjectDid, nonce) = parseFromJwt(
                credentialRequest.proof?.jwt ?: throw IllegalArgumentException("No proof.jwt in credential request!")
            )

            log.debug { "RETRIEVING VC FROM TOKEN MAPPING: $nonce" }
            val issuanceSessionData = tokenCredentialMapping[nonce]
                ?: throw IllegalArgumentException("The issuanceIdCredentialMapping does not contain a mapping for: $nonce!")

            val credentialResults = issuanceSessionData.map { data ->
                CredentialResponse.success(
                    format = credentialRequest.format,
                    credential = JsonPrimitive(
                        runBlocking {
                            val vc = data.request.credentialData

                            data.run {
                                when (credentialRequest.format) {
                                    CredentialFormat.sd_jwt_vc -> vc.mergingSdJwtIssue(
                                        issuerKey = issuerKey,
                                        issuerDid = issuerDid,
                                        subjectDid = subjectDid,
                                        mappings = request.mapping ?: JsonObject(emptyMap()),
                                        additionalJwtHeader = emptyMap(),
                                        additionalJwtOptions = emptyMap(),
                                        disclosureMap = data.request.selectiveDisclosure
                                            ?: SDMap.Companion.generateSDMap(
                                                JsonObject(emptyMap()),
                                                JsonObject(emptyMap())
                                            )
                                    )

                                    else -> vc.mergingJwtIssue(
                                        issuerKey = issuerKey,
                                        issuerDid = issuerDid,
                                        subjectDid = subjectDid,
                                        mappings = request.mapping ?: JsonObject(emptyMap()),
                                        additionalJwtHeader = emptyMap(),
                                        additionalJwtOptions = emptyMap(),
                                    )
                                }

                            }.also { log.debug { "Respond VC: $it" } }
                        }
                    )
                )
            }

            return BatchCredentialResponse.success(credentialResults, accessToken, 5.minutes)
        }
    }


    data class IssuanceSessionData(
        val issuerKey: Key, val issuerDid: String, val request: IssuanceRequest,
    )

    // TODO: Hack as this is non stateless because of oidc4vc lib API
    val sessionCredentialPreMapping = HashMap<String, List<IssuanceSessionData>>() // session id -> VC

    // TODO: Hack as this is non stateless because of oidc4vc lib API
    private val tokenCredentialMapping = HashMap<String, List<IssuanceSessionData>>() // token -> VC

    //private val sessionTokenMapping = HashMap<String, String>() // session id -> token

    // TODO: Hack as this is non stateless because of oidc4vc lib API
    fun setIssuanceDataForIssuanceId(issuanceId: String, data: List<IssuanceSessionData>) {
        log.debug { "DEPOSITED CREDENTIAL FOR ISSUANCE ID: $issuanceId" }
        sessionCredentialPreMapping[issuanceId] = data
    }

    // TODO: Hack as this is non stateless because of oidc4vc lib API
    fun mapSessionIdToToken(sessionId: String, token: String) {
        log.debug { "MAPPING SESSION ID TO TOKEN: $sessionId -->> $token" }
        val premappedVc = sessionCredentialPreMapping.remove(sessionId)
            ?: throw IllegalArgumentException("No credential pre-mapped with any such session id: $sessionId (for use with token: $token)")
        log.debug { "SWAPPING PRE-MAPPED VC FROM SESSION ID TO NEW TOKEN: $token" }
        tokenCredentialMapping[token] = premappedVc
    }
}
