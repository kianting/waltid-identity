package id.walt.webwallet.db

import id.walt.webwallet.config.ConfigManager
import id.walt.webwallet.config.DatasourceConfiguration
import id.walt.webwallet.db.models.*
import id.walt.webwallet.service.account.AccountsService
import id.walt.webwallet.service.credentials.CredentialsService
import id.walt.webwallet.service.issuers.IssuersService
import id.walt.webwallet.web.model.EmailAccountRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.uuid.UUID
import kotlinx.uuid.generateUUID
import kotlinx.uuid.randomUUID
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.bridge.SLF4JBridgeHandler
import java.sql.Connection
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.random.Random

object Db {

    private val log = KotlinLogging.logger { }

    lateinit var datasourceConfig: DatasourceConfiguration

    val dataDirectoryPath = Path("data")

    private fun connect() {
        datasourceConfig = ConfigManager.getConfig<DatasourceConfiguration>()
        val hikariDataSourceConfig = datasourceConfig.hikariDataSource

        // connect
        log.info { "Connecting to database at \"${hikariDataSourceConfig.jdbcUrl}\"..." }

        if (hikariDataSourceConfig.jdbcUrl.contains("sqlite")) {
            println("Will use sqlite database (${hikariDataSourceConfig.jdbcUrl}), working directory: ${Path(".").absolutePathString()}")
        }

        Database.connect(hikariDataSourceConfig)
        TransactionManager.manager.defaultIsolationLevel =
            toTransactionIsolationLevel(hikariDataSourceConfig.transactionIsolation)
    }

    // Make sure the creation order is correct (references / foreignKeys have to exist)
    val tables = listOf(
        Accounts,
        Wallets,
        WalletOperationHistories,
        WalletDids,
        WalletKeys,
        WalletCredentials,
        AccountWalletMappings,

        Web3Wallets,
        WalletIssuers,
        Events,
        WalletCategory,
        WalletCredentialCategoryMap,

        OidcLogins,
        WalletSettings,
    ).toTypedArray()
    
    
    fun recreateDatabase() {
        transaction {
            addLogger(StdOutSqlLogger)
            
            
            SchemaUtils.drop(*(tables.reversedArray()))
            SchemaUtils.create(*tables)
            
            runBlocking {

                AccountsService.register(request = EmailAccountRequest("Max Mustermann", "string@string.string", "string"))
                val accountResult = AccountsService.register(request = EmailAccountRequest("Max Mustermann", "user@email.com", "password"))
                val accountId = accountResult.getOrNull()?.id!!
                val walletResult = AccountsService.getAccountWalletMappings("", accountId)
                val walletId = walletResult.wallets.get(0).id
                
                IssuersService.add(
                    wallet = walletId,
                    name = "walt.id",
                    description = "walt.id issuer portal",
                    uiEndpoint = "https://portal.walt.id/credentials?ids=",
                    configurationEndpoint = "https://issuer.portal.walt.id/.well-known/openid-credential-issuer"
                )
                
                CredentialsService.add(
                    wallet = walletId,
                    WalletCredential(
                        wallet = walletId,
                        id = "urn:uuid:" + UUID.generateUUID(Random),
                        document = IssuanceExamples.universityDegreeCredential,
                        disclosures = null,
                        addedOn = Clock.System.now(),
                        manifest = null,
                        deletedOn = null,
                    )
                )
            }
        }
    }

    fun start() {
        connect()

        SLF4JBridgeHandler.removeHandlersForRootLogger()
        SLF4JBridgeHandler.install()

        if (datasourceConfig.recreateDatabaseOnStart) {
            recreateDatabase()
        } else {
            transaction {
                SchemaUtils.createMissingTablesAndColumns(*tables)
            }
        }
    }

    private fun toTransactionIsolationLevel(value: String): Int = when (value) {
        "TRANSACTION_NONE" -> Connection.TRANSACTION_NONE
        "TRANSACTION_READ_UNCOMMITTED" -> Connection.TRANSACTION_READ_UNCOMMITTED
        "TRANSACTION_READ_COMMITTED" -> Connection.TRANSACTION_READ_COMMITTED
        "TRANSACTION_REPEATABLE_READ" -> Connection.TRANSACTION_REPEATABLE_READ
        "TRANSACTION_SERIALIZABLE" -> Connection.TRANSACTION_SERIALIZABLE
        else -> Connection.TRANSACTION_SERIALIZABLE
    }
}
