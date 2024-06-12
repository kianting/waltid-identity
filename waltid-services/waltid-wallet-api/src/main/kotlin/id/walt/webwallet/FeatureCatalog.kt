package id.walt.webwallet

import id.walt.commons.featureflag.BaseFeature
import id.walt.commons.featureflag.OptionalFeature
import id.walt.commons.featureflag.ServiceFeatureCatalog
import id.walt.webwallet.config.*

object FeatureCatalog : ServiceFeatureCatalog {

    val databaseFeature = BaseFeature("db", "Database manager", DatasourceConfiguration::class)
    val loginsMethodFeature = BaseFeature("logins", "Logins method management", LoginMethodsConfig::class)
    val authenticationFeature = BaseFeature("auth", "Base authentication system", AuthConfig::class)

    val tenantFeature = OptionalFeature("tenant", "Cloud-based tenant management", TenantConfig::class, false)
    val pushFeature = OptionalFeature("push", "Push notifications", PushConfig::class, false)

    val runtimeMockFeature = OptionalFeature("runtime", "Runtime mock provider configuration", RuntimeConfig::class, false)

    val oidcAuthenticationFeature = OptionalFeature("oidc", "OIDC login feature", OidcConfiguration::class, false)
    val trustFeature = OptionalFeature("trust", "Trust records", TrustConfig::class, false)
    val rejectionReasonsFeature = OptionalFeature("rejectionreason", "Rejection reasons use case", RejectionReasonConfig::class, false)

    val registrationDefaultsFeature =
        OptionalFeature("registration-defaults", "Registration defaults (key, did) configuration", RegistrationDefaultsConfig::class, true)
    val keyGenerationDefaultsFeature = OptionalFeature(
        "key-generation-defaults",
        "Key generation defaults (key backend & generation config) configuration",
        KeyGenerationDefaultsConfig::class,
        true
    )

    val notificationFeature = OptionalFeature("notification", "Notification dispatch use case", NotificationConfig::class, false)

    override val baseFeatures = listOf(databaseFeature, loginsMethodFeature, authenticationFeature)
    override val optionalFeatures = listOf(
        tenantFeature,
        pushFeature,
        runtimeMockFeature,
        oidcAuthenticationFeature,
        trustFeature,
        rejectionReasonsFeature,
        registrationDefaultsFeature,
        keyGenerationDefaultsFeature,
        notificationFeature,
        runtimeMockFeature
    )
}
