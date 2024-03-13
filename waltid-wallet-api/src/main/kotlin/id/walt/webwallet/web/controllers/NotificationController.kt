package id.walt.webwallet.web.controllers

import id.walt.webwallet.notificationusecase.NotificationFilterParameter
import id.walt.webwallet.notificationusecase.NotificationUseCase
import id.walt.webwallet.service.notifications.NotificationService
import id.walt.webwallet.service.push.PushManager
import io.github.smiley4.ktorswaggerui.dsl.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.uuid.UUID
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets


object NotificationController {

    /*
    fun splitQuery(url: URI): Map<String?, List<String?>?> {
        return if (url.query.isEmpty()) {
            emptyMap<String?, List<String?>>()
        } else url.query.split("&")
    }*/

    private fun splitQueryParameter(it: String): Pair<String, String> {
        val idx = it.indexOf("=")
        val key = if (idx > 0) it.substring(0, idx) else it
        val value = if (idx > 0 && it.length > idx + 1) it.substring(idx + 1) else null
        return Pair(
            URLDecoder.decode(key, StandardCharsets.UTF_8),
            URLDecoder.decode(value, StandardCharsets.UTF_8)
        )
    }

    fun Application.notifications() {
        walletRoute {
            val useCase = NotificationUseCase(NotificationService)
            route("/api/notifications", {
                tags = listOf("NotificationController")
            }) {
                get({
                    summary = "Get notifications"
                    request {
                        queryParameter<String>("type") {
                            description = "Filter by notification type"
                            example = "Receive"
                        }
                        queryParameter<String>("addedOn") {
                            description = "Filter by date the notification was created"
                            example = "2024-03-06T12:48:50.723Z"
                        }
                        queryParameter<Boolean>("isRead") {
                            description = "Filter by 'isRead' status"
                            example = false
                        }
                        queryParameter<String>("sort") {
                            description = "Sort by date added: ASC or DESC"
                            example = "ASC"
                        }
                    }
                    response {
                        HttpStatusCode.OK to {
                            description = "Array of notification objects"
                            body<List<JsonObject>>()
                        }
                    }
                }) {
                    context.respond(
                        useCase.findAll(
                            getWalletId(), NotificationFilterParameter(
                                type = call.request.queryParameters["type"],
                                isRead = call.request.queryParameters["isRead"]?.toBooleanStrictOrNull(),
                                addedOn = call.request.queryParameters["addedOn"],
                                sort = call.request.queryParameters["sort"] ?: "desc",
                            )
                        )
                    )
                }
                delete({
                    summary = "Delete all wallet notifications"
                    response {
                        HttpStatusCode.Accepted to { description = "Notifications deleted" }
                        HttpStatusCode.BadRequest to { description = "Notifications could not be deleted" }
                    }
                }) {
                    context.respond(if (useCase.deleteAll(getWalletId()) > 0) HttpStatusCode.Accepted else HttpStatusCode.BadRequest)
                }
                put("status", {
                    summary = "Set notification read status"
                    request {
                        body<List<String>> {
                            description = "The list of notification ids"
                            required = true
                        }
                    }
                    response {
                        HttpStatusCode.Accepted to { description = "Notification status updated" }
                        HttpStatusCode.BadRequest to { description = "Notification status could not be updated" }
                    }
                }) {
                    val ids = call.receive<List<String>>()
                    val status = call.parameters.getOrFail("status").toBoolean()
                    context.respond(
                        if (useCase.setStatus(
                                *ids.map { UUID(it) }.toTypedArray(), isRead = status
                            ) > 0
                        ) HttpStatusCode.Accepted else HttpStatusCode.BadRequest
                    )
                }
                route("id"){
                    get({
                        summary = "Get notification by id"
                        response {
                            HttpStatusCode.OK to {
                                description = "Notification object"
                                body<JsonObject>()
                            }
                        }
                    }) {
                        val id = call.parameters.getOrFail("id")
                        context.respond(useCase.findById(UUID(id)).onSuccess {
                            it
                        }.onFailure {
                            it.localizedMessage
                        })
                    }
                    delete({
                        summary = "Delete notification by id"
                        response {
                            HttpStatusCode.Accepted to { description = "Notification deleted" }
                            HttpStatusCode.BadRequest to { description = "Notification could not be deleted" }
                        }
                    }) {
                        val id = call.parameters.getOrFail("id")
                        context.respond(if (useCase.deleteById(UUID(id)) > 0) HttpStatusCode.Accepted else HttpStatusCode.BadRequest)
                    }
                }

                post("send", {
                    summary = "Experimental: Push notification system"
                    // TODO
                }) {
                    var id = call.request.queryParameters["id"] ?: return@post call.respond(HttpStatusCode.OK)
                    val type = call.request.queryParameters["type"] ?: "issuance"

                    id = "did:key:z6Mkipa1mwZTvUaTCPkHsdKGWNWteQbpEmvcr9HFed9gS4Ye"

                    var offer = call.receiveText().trim()

                    if (offer[0] == '{') {
                        offer = Json.parseToJsonElement(offer).jsonObject["url"]!!.jsonPrimitive.content
                    }

                    println("Got notification for $id for: $offer")

                    val queries = URI(offer).query.split("&").groupBy(
                        keySelector = { splitQueryParameter(it).first }, valueTransform = { splitQueryParameter(it).second }
                    )

                    when (type) {
                        "issuance" -> {
                            val issuer = URL(queries["issuer"]!!.first()).host
                            val credentialTypes = queries["credential_type"]!!

                            PushManager.sendIssuanceNotification(id, issuer, credentialTypes, offer)
                        }

                        "verification" -> {
                            val remoteHost = URL(queries["redirect_uri"]!!.first()).host

                            PushManager.sendVerificationNotification(id, remoteHost, listOf("TODO"), offer)
                        }
                    }


                    call.respond(HttpStatusCode.OK, "Queued.")
                }
            }
        }
    }
}
