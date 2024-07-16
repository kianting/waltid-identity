package id.walt.commons.web

import io.ktor.http.*

open class WebException(val status: HttpStatusCode, message: String) : Exception(message)

class UnauthorizedException(message: String) : WebException(HttpStatusCode.Unauthorized, message)
class ConflictException(message: String) : WebException(HttpStatusCode.Conflict, message)
