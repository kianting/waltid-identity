package id.walt.webwallet.web.controllers

import id.walt.crypto.keys.KeyGenerationRequest
import io.github.smiley4.ktorswaggerui.dsl.delete
import io.github.smiley4.ktorswaggerui.dsl.get
import io.github.smiley4.ktorswaggerui.dsl.post
import io.github.smiley4.ktorswaggerui.dsl.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.sql.transactions.transaction

fun Application.keys() = walletRoute {
    route("keys", {
        tags = listOf("Keys")
    }) {
        get({
            summary = "List Keys"
            response {
                HttpStatusCode.OK to {
                    description = "Array of (key) strings"
                    body<List<String>>()
                }
            }
        }) {
            context.respond(getWalletService().run { transaction { runBlocking { listKeys() } } })
        }

        post("generate", {
            summary = "Generate new key"
            request {
                body<KeyGenerationRequest> {
                    description = "Key configuration (JSON)"

                    example(
                        "OCI key generation request",
                        buildJsonObject {
                            put("backend", JsonPrimitive("oci"))
                            put(
                                "config",
                                buildJsonObject {
                                    put(
                                        "tenancyOcid",
                                        JsonPrimitive("ocid1.tenancy.oc1..aaaaaaaaiijfupfvsqwqwgupzdy5yclfzcccmie4ktp2wlgslftv5j7xpk6q")
                                    )
                                    put(
                                        "compartmentOcid",
                                        JsonPrimitive("ocid1.compartment.oc1..aaaaaaaaxjkkfjqxdqk7ldfjrxjmacmbi7sci73rbfiwpioehikavpbtqx5q")
                                    )
                                    put(
                                        "userOcid",
                                        JsonPrimitive("ocid1.user.oc1..aaaaaaaaxjkkfjqxdqk7ldfjrxjmacmbi7sci73rbfiwpioehikavpbtqx5q")
                                    )
                                    put("fingerprint", JsonPrimitive("bb:d4:4b:0c:c8:3a:49:15:7f:87:55:d5:2b:7e:dd:bc"))
                                    put(
                                        "cryptoEndpoint",
                                        JsonPrimitive("ens7pgl2aaam2-crypto.kms.eu-frankfurt-1.oraclecloud.com")
                                    )
                                    put(
                                        "managementEndpoint",
                                        JsonPrimitive("ens7pgl2aaam2-management.kms.eu-frankfurt-1.oraclecloud.com")
                                    )
                                    put("signingKeyPem", JsonPrimitive("privateKey"))
                                }
                            )
                            put("keyType", JsonPrimitive("secp256r1"))
                        }
                            .toString())
                    example(
                        "JWK key generation request",
                        buildJsonObject {
                            put("backend", JsonPrimitive("jwk"))
                            put("keyType", JsonPrimitive("Ed25519"))
                        }
                            .toString()
                    )
                    example(
                        "TSE key generation request",
                        buildJsonObject {
                            put("backend", JsonPrimitive("tse"))
                            put("config",
                                buildJsonObject {
                                    put("server", JsonPrimitive("http://0.0.0.0:8200/v1/transit"))
                                    put("accessKey", JsonPrimitive("dev-only-token"))
                                })
                            put("keyType", JsonPrimitive("Ed25519"))

                        }.toString()
                    )
                }
            }
        }) {
            val keyGenerationRequest = context.receive<KeyGenerationRequest>()

            runCatching {
                getWalletService().generateKey(keyGenerationRequest)
            }.onSuccess {
                context.respond(it)
            }.onFailure {
                context.respond(HttpStatusCode.BadRequest, it.localizedMessage)
            }
        }


        post("import", {
            summary = "Import an existing key"
            request {
                body<String> { description = "Key in JWK or PEM format" }
            }
        }) {
            val body = call.receiveText()
            getWalletService().importKey(body)

            context.respond(HttpStatusCode.OK)
        }

        get("load/{alias}", {
            summary = "Show a specific key"
            request {
                pathParameter<String>("alias") {
                    description = "Key to show"

                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The key document (JSON)"
                    body<JsonObject>()
                }
            }
        }) {
            context.respond(
                getWalletService().loadKey(
                    context.parameters["alias"] ?: throw IllegalArgumentException("No key supplied")
                )
            )
        }

        get("meta/{alias}", {
            summary = "Show a specific key"
            request {
                pathParameter<String>("alias") {
                    description = "Key to show"

                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The key document (JSON)"
                    body<JsonObject>()
                }
            }
        }) {
            val keyId = context.parameters["alias"] ?: error("No key supplied")
            context.respond(getWalletService().loadKey(keyId))
        }

        get("export/{keyId}", {
            summary = "Load a specific key"

            request {
                pathParameter<String>("keyId") {
                    description = "the key id (or alias)"
                    example = "bc6fa6b0593648238c4616800bed7746"
                }
                queryParameter<String>("format") {
                    description = "Select format to export the key, e.g. 'JWK' / 'PEM'. JWK by default."
                    example = "JWK"
                    required = false
                }
                queryParameter<Boolean>("loadPrivateKey") {
                    description =
                        "Select if the secret private key should be loaded - take special care in this case! False by default."
                    example = false
                    required = false
                }
            }
        }) {
            val keyId = context.parameters["keyId"] ?: throw IllegalArgumentException("No key id provided.")

            val format = context.request.queryParameters["format"] ?: "JWK"
            val loadPrivateKey = context.request.queryParameters["loadPrivateKey"].toBoolean()

            context.respond(getWalletService().exportKey(keyId, format, loadPrivateKey))
        }

        delete("{keyId}", {
            summary = "Delete a specific key"
            request {
                pathParameter<String>("keyId") {
                    description = "the key id (or alias)"
                    example = "bc6fa6b0593648238c4616800bed7746"
                }
            }
            response {
                HttpStatusCode.Accepted to { description = "Key deleted" }
                HttpStatusCode.BadRequest to { description = "Key could not be deleted" }
            }
        }) {
            val keyId = context.parameters["keyId"] ?: throw IllegalArgumentException("No key id provided.")

            val success = getWalletService().deleteKey(keyId)

            context.respond(if (success) HttpStatusCode.Accepted else HttpStatusCode.BadRequest)
        }
    }
}
