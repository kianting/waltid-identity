package id.walt.webwallet.service

import id.walt.webwallet.config.ConfigManager
import id.walt.webwallet.config.OidcConfiguration
import id.walt.webwallet.config.TrustConfig
import id.walt.webwallet.db.models.AccountWalletMappings
import id.walt.webwallet.db.models.AccountWalletPermissions
import id.walt.webwallet.db.models.Wallets
import id.walt.webwallet.seeker.DefaultCredentialTypeSeeker
import id.walt.webwallet.service.account.AccountsService
import id.walt.webwallet.service.category.CategoryServiceImpl
import id.walt.webwallet.service.credentials.CredentialsService
import id.walt.webwallet.service.dids.DidsService
import id.walt.webwallet.service.events.EventService
import id.walt.webwallet.service.exchange.IssuanceService
import id.walt.webwallet.service.issuers.IssuersService
import id.walt.webwallet.service.notifications.NotificationService
import id.walt.webwallet.service.settings.SettingsService
import id.walt.webwallet.service.trust.DefaultIssuerNameResolveService
import id.walt.webwallet.service.trust.DefaultTrustValidationService
import id.walt.webwallet.usecase.claim.ExplicitClaimStrategy
import id.walt.webwallet.usecase.claim.SilentClaimStrategy
import id.walt.webwallet.usecase.event.EventFilterUseCase
import id.walt.webwallet.usecase.event.EventUseCase
import id.walt.webwallet.usecase.exchange.MatchPresentationDefinitionCredentialsUseCase
import id.walt.webwallet.usecase.issuer.IssuerUseCaseImpl
import id.walt.webwallet.usecase.notification.NotificationFilterUseCase
import id.walt.webwallet.usecase.notification.NotificationUseCase
import id.walt.webwallet.utils.WalletHttpClients.getHttpClient
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import kotlinx.uuid.UUID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.concurrent.ConcurrentHashMap

object WalletServiceManager {

    private val walletServices = ConcurrentHashMap<Pair<UUID, UUID>, WalletService>()
    private val categoryService = CategoryServiceImpl
    private val settingsService = SettingsService
    private val httpClient = getHttpClient()
    private val trustConfig by lazy { ConfigManager.getConfig<TrustConfig>() }
    private val credentialService = CredentialsService()
    private val credentialTypeSeeker = DefaultCredentialTypeSeeker()
    private val eventService = EventService()
    val eventUseCase = EventUseCase(eventService)
    val eventFilterUseCase = EventFilterUseCase(eventService)
    val oidcConfig by lazy { ConfigManager.getConfig<OidcConfiguration>() }
    val issuerUseCase = IssuerUseCaseImpl(service = IssuersService, http = httpClient)
    val issuerTrustValidationService = DefaultTrustValidationService(httpClient, trustConfig.issuersRecord)
    val verifierTrustValidationService = DefaultTrustValidationService(httpClient, trustConfig.verifiersRecord)
    val notificationUseCase = NotificationUseCase(NotificationService, httpClient)
    val notificationFilterUseCase = NotificationFilterUseCase(NotificationService, credentialService)
    val matchPresentationDefinitionCredentialsUseCase = MatchPresentationDefinitionCredentialsUseCase(credentialService)
    val silentClaimStrategy = SilentClaimStrategy(
        issuanceService = IssuanceService,
        credentialService = credentialService,
        issuerTrustValidationService = issuerTrustValidationService,
        issuerNameResolveService = DefaultIssuerNameResolveService(httpClient, trustConfig.issuersRecord),
        accountService = AccountsService,
        didService = DidsService,
        issuerUseCase = issuerUseCase,
        eventUseCase = eventUseCase,
        notificationUseCase = notificationUseCase,
        credentialTypeSeeker = credentialTypeSeeker,
    )
    val explicitClaimStrategy = ExplicitClaimStrategy(
        issuanceService = IssuanceService,
        credentialService = credentialService,
        eventUseCase = eventUseCase,
    )

    fun getWalletService(tenant: String, account: UUID, wallet: UUID): WalletService =
        walletServices.getOrPut(Pair(account, wallet)) {
            SSIKit2WalletService(
                tenant = tenant,
                accountId = account,
                walletId = wallet,
                categoryService = categoryService,
                settingsService = settingsService,
                eventUseCase = eventUseCase,
                http = httpClient
            )
        }

    fun createWallet(tenant: String, forAccount: UUID): UUID {
        val accountName = AccountsService.get(forAccount).email

        // TODO: remove testing code / lock behind dev-mode
        if (accountName?.contains("multi-wallet") == true) {
            val second = Wallets.insert {
                it[name] = "ABC Company wallet"
                it[createdOn] = Clock.System.now().toJavaInstant()
            }[Wallets.id].value

            AccountWalletMappings.insert {
                it[AccountWalletMappings.tenant] = tenant
                it[accountId] = forAccount
                it[wallet] = second
                it[permissions] = AccountWalletPermissions.READ_ONLY
                it[addedOn] = Clock.System.now().toJavaInstant()
            }
        }

        val walletId = Wallets.insert {
            it[name] = "Wallet of $accountName"
            it[createdOn] = Clock.System.now().toJavaInstant()
        }[Wallets.id].value

        println("Creating wallet mapping: $forAccount -> $walletId")
        AccountWalletMappings.insert {
            it[AccountWalletMappings.tenant] = tenant
            it[accountId] = forAccount
            it[wallet] = walletId
            it[permissions] = AccountWalletPermissions.ADMINISTRATE
            it[addedOn] = Clock.System.now().toJavaInstant()
        }

        return walletId
    }

    @Deprecated(
        replaceWith = ReplaceWith(
            "AccountsService.getAccountWalletMappings(account)", "id.walt.service.account.AccountsService"
        ), message = "depreacted"
    )
    fun listWallets(tenant: String, account: UUID): List<UUID> =
        AccountWalletMappings.innerJoin(Wallets)
            .selectAll().where { (AccountWalletMappings.tenant eq tenant) and (AccountWalletMappings.accountId eq account) }.map {
                it[Wallets.id].value
            }
}
