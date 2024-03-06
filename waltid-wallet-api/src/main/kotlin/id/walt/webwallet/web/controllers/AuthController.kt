package id.walt.webwallet.web.controllers

import id.walt.webwallet.config.ConfigManager
import id.walt.webwallet.config.OidcConfiguration
import id.walt.webwallet.config.WebConfig
import id.walt.webwallet.db.models.AccountWalletMappings
import id.walt.webwallet.db.models.AccountWalletPermissions
import id.walt.webwallet.service.OidcLoginService
import id.walt.webwallet.service.WalletServiceManager
import id.walt.webwallet.service.account.AccountsService
import id.walt.webwallet.utils.RandomUtils
import id.walt.webwallet.web.ForbiddenException
import id.walt.webwallet.web.InsufficientPermissionsException
import id.walt.webwallet.web.UnauthorizedException
import id.walt.webwallet.web.WebBaseRoutes.webWalletRoute
import id.walt.webwallet.web.model.AccountRequest
import id.walt.webwallet.web.model.EmailAccountRequest
import id.walt.webwallet.web.model.KeycloakAccountRequest
import id.walt.webwallet.web.model.LoginRequestJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.smiley4.ktorswaggerui.dsl.get
import io.github.smiley4.ktorswaggerui.dsl.post
import io.github.smiley4.ktorswaggerui.dsl.route
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.ktor.util.pipeline.*
import kotlin.collections.set
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.uuid.SecureRandom
import kotlinx.uuid.UUID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

private val log = KotlinLogging.logger {}

@Suppress("ArrayInDataClass")
data class ByteLoginRequest(val username: String, val password: ByteArray) {
  constructor(
      loginRequest: EmailAccountRequest
  ) : this(loginRequest.email, loginRequest.password.toByteArray())

  override fun toString() = "[LOGIN REQUEST FOR: $username]"
}

fun generateToken() = RandomUtils.randomBase64UrlString(256)

data class LoginTokenSession(val token: String) : Principal

data class OidcTokenSession(val token: String) : Principal

object AuthKeys {
  private val secureRandom = SecureRandom

  // TODO make statically configurable for HA deployments
  val encryptionKey = secureRandom.nextBytes(16)
  val signKey = secureRandom.nextBytes(16)
}

fun Application.configureSecurity() {
  val webConfig = ConfigManager.getConfig<WebConfig>()
  val oidcConfig = ConfigManager.getConfig<OidcConfiguration>()
  install(Sessions) {
    cookie<LoginTokenSession>("login") {
      // cookie.encoding = CookieEncoding.BASE64_ENCODING

      // cookie.httpOnly = true
      cookie.httpOnly = false // FIXME
      // TODO cookie.secure = true
      cookie.maxAge = 1.days
      cookie.extensions["SameSite"] = "Strict"
      transform(SessionTransportTransformerEncrypt(AuthKeys.encryptionKey, AuthKeys.signKey))
    }
    cookie<OidcTokenSession>("oidc-login") {
      // cookie.encoding = CookieEncoding.BASE64_ENCODING

      // cookie.httpOnly = true
      cookie.httpOnly = false // FIXME
      // TODO cookie.secure = true
      cookie.maxAge = 1.days
      cookie.extensions["SameSite"] = "Strict"
      transform(SessionTransportTransformerEncrypt(AuthKeys.encryptionKey, AuthKeys.signKey))
    }
  }

  install(Authentication) {
    oauth("auth-oauth") {
      client = HttpClient()
      providerLookup = {
        OAuthServerSettings.OAuth2ServerSettings(
            name = oidcConfig.providerName,
            authorizeUrl = oidcConfig.authorizeUrl,
            accessTokenUrl = oidcConfig.accessTokenUrl,
            clientId = oidcConfig.clientId,
            clientSecret = oidcConfig.clientSecret,
            accessTokenRequiresBasicAuth = false,
            requestMethod = HttpMethod.Post,
            defaultScopes = listOf("roles"))
      }
      urlProvider = { "${webConfig.publicBaseUrl}/wallet-api/auth/oidc-session" }
    }

    jwt("auth-oauth-jwt") {
      realm = OidcLoginService.oidcRealm
      // verifier(jwkProvider, oidcRealm)
      verifier(OidcLoginService.jwkProvider)

      validate { credential ->
        JWTPrincipal(credential.payload)

        /*if (jwtCredential.payload.issuer != null) {
            JWTPrincipal(jwtCredential.payload)
        } else {
            null
        }*/
      }
      challenge { defaultScheme, realm ->
        call.respond(HttpStatusCode.Unauthorized, "Token is not valid or has expired")
      }
    }

    bearer("auth-bearer") {
      authenticate { tokenCredential ->
        if (securityUserTokenMapping.contains(tokenCredential.token)) {
          UserIdPrincipal(securityUserTokenMapping[tokenCredential.token].toString())
        } else {
          null
        }
      }
    }

    session<LoginTokenSession>("auth-session") {
      validate { session ->
        if (securityUserTokenMapping.contains(session.token)) {
          UserIdPrincipal(securityUserTokenMapping[session.token].toString())
        } else {
          sessions.clear("login")
          null
        }
      }

      challenge {
        call.respond(
            HttpStatusCode.Unauthorized,
            JsonObject(mapOf("message" to JsonPrimitive("Login Required"))))
      }
    }
  }
}

val securityUserTokenMapping = HashMap<String, UUID>() // Token -> UUID

fun Application.auth() {
  webWalletRoute {
    route("auth", { tags = listOf("Authentication") }) {
      authenticate("auth-oauth") {
        get(
            "oidc-login",
            {
              description = "Redirect to OIDC provider for login"
              response { HttpStatusCode.Found }
            }) {
              call.respondRedirect("oidc-session")
            }

        authenticate("auth-oauth-jwt") {
          get("oidc-session", { description = "Configure OIDC session" }) {
            val principal: OAuthAccessTokenResponse.OAuth2 =
                call.principal() ?: error("No OAuth principal")

            call.sessions.set(OidcTokenSession(principal.accessToken))

            call.respondRedirect("/login?oidc_login=true")
          }
        }
      }

      get("oidc-token", { description = "Returns OIDC token" }) {
        val oidcSession = call.sessions.get<OidcTokenSession>() ?: error("No OIDC session")

        call.respond(oidcSession.token)
      }

      post(
          "login",
          {
            summary =
                "Login with [email + password] or [wallet address + ecosystem] or [oidc session]"
            request {
              body<EmailAccountRequest> {
                example(
                    "E-mail + password",
                    buildJsonObject {
                          put("type", JsonPrimitive("email"))
                          put("email", JsonPrimitive("user@email.com"))
                          put("password", JsonPrimitive("password"))
                        }
                        .toString())
                example(
                    "Wallet address + ecosystem",
                    buildJsonObject {
                          put("type", JsonPrimitive("address"))
                          put("address", JsonPrimitive("0xABC"))
                          put("ecosystem", JsonPrimitive("ecosystem"))
                        }
                        .toString())
                example(
                    "OIDC token",
                    buildJsonObject {
                          put("type", JsonPrimitive("oidc"))
                          put("token", JsonPrimitive("oidc token"))
                        }
                        .toString())
              }
            }
            response {
              HttpStatusCode.OK to { description = "Login successful" }
              HttpStatusCode.Unauthorized to { description = "Login failed" }
              HttpStatusCode.BadRequest to { description = "Login failed" }
            }
          }) {
            val reqBody = LoginRequestJson.decodeFromString<AccountRequest>(call.receive())
            AccountsService.authenticate("", reqBody)
                .onSuccess { // FIX ME -> TENANT HERE
                  securityUserTokenMapping[it.token] = it.id
                  call.sessions.set(LoginTokenSession(it.token))
                  call.response.status(HttpStatusCode.OK)
                  call.respond(
                      mapOf(
                          "token" to it.token, "id" to it.id.toString(), "username" to it.username))
                }
                .onFailure {
                  it.printStackTrace()
                  call.respond(HttpStatusCode.BadRequest, it.localizedMessage)
                }
          }

      post(
          "create",
          {
            summary = "Register with [email + password] or [wallet address + ecosystem]"
            request {
              body<EmailAccountRequest> {
                example(
                    "E-mail + password",
                    buildJsonObject {
                          put("name", JsonPrimitive("Max Mustermann"))
                          put("email", JsonPrimitive("user@email.com"))
                          put("password", JsonPrimitive("password"))
                          put("type", JsonPrimitive("email"))
                        }
                        .toString())
                example(
                    "Wallet address + ecosystem",
                    buildJsonObject {
                          put("address", JsonPrimitive("0xABC"))
                          put("ecosystem", JsonPrimitive("ecosystem"))
                          put("type", JsonPrimitive("address"))
                        }
                        .toString())
              }
            }
            response {
              HttpStatusCode.Created to { description = "Register successful" }
              HttpStatusCode.BadRequest to { description = "Register failed" }
            }
          }) {
            val req = LoginRequestJson.decodeFromString<AccountRequest>(call.receive())
            AccountsService.register("", req)
                .onSuccess {
                  call.response.status(HttpStatusCode.Created)
                  call.respond("Registration succeed.")
                }
                .onFailure { call.respond(HttpStatusCode.BadRequest, it.localizedMessage) }
          }

      // create keycloak User
      post(
          "keycloak/create",
          {
            summary = "Register with [email + password] or [wallet address + ecosystem] On Keycloak"
            request {
              body<KeycloakAccountRequest> {
                example(
                    "E-mail + password",
                    buildJsonObject {
                          put("name", JsonPrimitive("Max Mustermann"))
                          put("email", JsonPrimitive("user@email.com"))
                          put("password", JsonPrimitive("password"))
                          put("type", JsonPrimitive("email"))
                        }
                        .toString())
                example(
                    "Wallet address + ecosystem",
                    buildJsonObject {
                          put("address", JsonPrimitive("0xABC"))
                          put("ecosystem", JsonPrimitive("ecosystem"))
                          put("type", JsonPrimitive("address"))
                        }
                        .toString())
              }
            }
            response {
              HttpStatusCode.Created to { description = "Register successful" }
              HttpStatusCode.BadRequest to { description = "Register failed" }
            }
          }) {
            val req = LoginRequestJson.decodeFromString<AccountRequest>(call.receive())
            AccountsService.register("", req)
                .onSuccess {
                  call.response.status(HttpStatusCode.Created)
                  call.respond("Registration succeed.")
                }
                .onFailure { call.respond(HttpStatusCode.BadRequest, it.localizedMessage) }
          }

      authenticate("auth-session", "auth-bearer") {
        get("user-info", { summary = "Return user ID if logged in" }) {
          call.respond(getUserId().name)
        }
        get("session", { summary = "Return session ID if logged in" }) {
          // val token = getUserId().name
          val token = getUsersSessionToken() ?: throw UnauthorizedException("Invalid session")

          if (securityUserTokenMapping.contains(token))
              call.respond(mapOf("token" to mapOf("accessToken" to token)))
          else throw UnauthorizedException("Invalid (outdated?) session!")
        }
      }

      post(
          "logout",
          {
            summary = "Logout (delete session)"
            response { HttpStatusCode.OK to { description = "Logged out." } }
          }) {
            val token = getUsersSessionToken()

            securityUserTokenMapping.remove(token)

            call.sessions.clear<LoginTokenSession>()

            val oidcSession = call.sessions.get<OidcTokenSession>()
            if (oidcSession != null) {
              call.sessions.clear<LoginTokenSession>()

              call.respond(HttpStatusCode.OK)
              // call.respondRedirect("http://localhost:8080/realms/waltid-keycloak-ktor/protocol/openid-connect/logout?post_logout_redirect_uri=http://localhost:3000&client_id=waltid_backend")
            } else {
              call.respond(HttpStatusCode.OK)
            }
          }
      get("logout-oidc", { description = "Logout via OIDC provider" }) {
        val oidcConfig = ConfigManager.getConfig<OidcConfiguration>()
        val webConfig = ConfigManager.getConfig<WebConfig>()
        call.respondRedirect(
            "${oidcConfig.logoutUrl}?post_logout_redirect_uri=${webConfig.publicBaseUrl}&client_id=${oidcConfig.clientId}")
      }
    }
  }
}

fun PipelineContext<Unit, ApplicationCall>.getUserId() =
    call.principal<UserIdPrincipal>("auth-session")
        ?: call.principal<UserIdPrincipal>("auth-bearer")
        ?: call.principal<UserIdPrincipal>() // bearer is registered with no name for some reason
        ?: throw UnauthorizedException("Could not find user authorization within request.")

fun PipelineContext<Unit, ApplicationCall>.getUserUUID() =
    runCatching { UUID(getUserId().name) }
        .getOrElse { throw IllegalArgumentException("Invalid user id: $it") }

fun PipelineContext<Unit, ApplicationCall>.getWalletId() =
    runCatching {
          UUID(call.parameters["wallet"] ?: throw IllegalArgumentException("No wallet ID provided"))
        }
        .getOrElse { throw IllegalArgumentException("Invalid wallet ID provided: ${it.message}") }

fun PipelineContext<Unit, ApplicationCall>.getWalletService(walletId: UUID) =
    WalletServiceManager.getWalletService("", getUserUUID(), walletId) // FIX ME -> TENANT HERE

fun PipelineContext<Unit, ApplicationCall>.getWalletService() =
    WalletServiceManager.getWalletService("", getUserUUID(), getWalletId()) // FIX ME -> TENANT HERE

fun PipelineContext<Unit, ApplicationCall>.getUsersSessionToken(): String? =
    call.sessions.get(LoginTokenSession::class)?.token
        ?: call.request.authorization()?.removePrefix("Bearer ")

fun getNftService() = WalletServiceManager.getNftService()

fun PipelineContext<Unit, ApplicationCall>.ensurePermissionsForWallet(
    required: AccountWalletPermissions
): Boolean {
  val userId = getUserUUID()
  val walletId = getWalletId()

  val permissions = transaction {
    (AccountWalletMappings.selectAll()
        .where {
          (AccountWalletMappings.tenant eq "") and
              (AccountWalletMappings.accountId eq userId) and
              (AccountWalletMappings.wallet eq walletId)
        } // FIX ME -> TENANT HERE
        .firstOrNull()
        ?: throw ForbiddenException("This account does not have access to the specified wallet."))[
        AccountWalletMappings.permissions]
  }

  if (permissions.power >= required.power) {
    return true
  } else {
    throw InsufficientPermissionsException(minimumRequired = required, current = permissions)
  }
}
