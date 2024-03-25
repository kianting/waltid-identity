package id.walt.webwallet.notificationusecase

import id.walt.webwallet.config.ConfigManager
import id.walt.webwallet.config.NotificationConfig
import id.walt.webwallet.db.models.Notification
import id.walt.webwallet.service.notifications.NotificationService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.Instant
import kotlinx.uuid.UUID

class NotificationUseCase(
    private val service: NotificationService,
    private val http: HttpClient,
) {
    private val logger = KotlinLogging.logger {}
    private val config by lazy { ConfigManager.getConfig<NotificationConfig>() }
    fun add(vararg notification: Notification) = service.add(notification.toList())
    fun setStatus(vararg id: UUID, isRead: Boolean) = id.mapNotNull {
        service.get(it).getOrNull()
    }.map {
        service.update(
            Notification(
                id = it.id,
                account = it.account,
                wallet = it.wallet,
                type = it.type,
                status = isRead,
                addedOn = it.addedOn,
                data = it.data
            )
        )
    }.size

    fun findAll(wallet: UUID, parameter: NotificationFilterParameter) = service.list(
        wallet = wallet,
        type = parameter.type,
        addedOn = parameter.addedOn,
        isRead = parameter.isRead,
        sortAscending = parseSortOrder(parameter.sort)
    )

    fun findById(id: UUID) = service.get(id)
    fun deleteById(id: UUID) = service.delete(id)
    fun deleteAll(wallet: UUID) = service.list(wallet).mapNotNull { it.id?.let { UUID(it) } }.let {
        service.delete(*it.toTypedArray())
    }

    suspend fun send(vararg notification: Notification) = notification.forEach {
        http.post(config.url) {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            config.apiKey?.let { bearerAuth(it) }
            setBody(it)
        }.also {
            logger.debug { "notification sent: ${it.status}" }
        }
    }

    private fun parseSortOrder(sort: String) = sort.lowercase().takeIf { it == "asc" }?.let { true } ?: false
}

data class NotificationFilterParameter(
    val type: String?,
    val isRead: Boolean?,
    val sort: String = "desc",
    val addedOn: String? = null,
)