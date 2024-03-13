package id.walt.webwallet.db.models

import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.Serializable
import kotlinx.uuid.UUID
import kotlinx.uuid.exposed.KotlinxUUIDTable
import kotlinx.uuid.exposed.kotlinxUUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.javatime.timestamp

object WalletNotifications : KotlinxUUIDTable("notifications") {
    //TODO: change to reference username
    val account = kotlinxUUID("account")
    val wallet = reference("wallet", Wallets)
    val isRead = bool("is_read").default(false)
    val type = varchar("type", 128)
    val addedOn = timestamp("added_on")
    val data = text("data")
}

@Serializable
data class Notification(
    val id: UUID? = null,
    val account: UUID,
    val wallet: UUID,
    val type: String,
    val status: Boolean,
    val addedOn: Instant,
    val data: String,
) {
    constructor(resultRow: ResultRow) : this(
        id = resultRow[WalletNotifications.id].value,
        account = resultRow[WalletNotifications.account],
        wallet = resultRow[WalletNotifications.wallet].value,
        type = resultRow[WalletNotifications.type],
        status = resultRow[WalletNotifications.isRead],
        addedOn = resultRow[WalletNotifications.addedOn].toKotlinInstant(),
        data = resultRow[WalletNotifications.data],
    )

    interface Data

    @Serializable
    data class CredentialData(
        val credentialId: String,
        val logo: String,
        val detail: String,
    ) : Data
}