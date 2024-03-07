package id.walt.webwallet.config

data class OidcConfiguration(
    val enableOidcLogin: Boolean,
    val providerName: String,
    val oidcRealm: String,
    val oidcJwks: String,
    val jwksCache: OidcJwksCacheConfiguration,
    val authorizeUrl: String,
    val accessTokenUrl: String,
    val logoutUrl: String,
    val clientId: String,
    val clientSecret: String,
    val oidcUserApi: String,
) : WalletConfig {
  data class OidcJwksCacheConfiguration(
      val cacheSize: Int,
      val cacheExpirationHours: Int,
      val rateLimit: JwksRateLimit
  ) {
    data class JwksRateLimit(val bucketSize: Int, val refillRateMinutes: Int)
  }
}
