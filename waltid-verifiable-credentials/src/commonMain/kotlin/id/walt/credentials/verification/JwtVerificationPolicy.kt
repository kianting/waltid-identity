package id.walt.credentials.verification

import love.forte.plugin.suspendtrans.annotation.JsPromise
import love.forte.plugin.suspendtrans.annotation.JvmAsync
import love.forte.plugin.suspendtrans.annotation.JvmBlocking
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@ExperimentalJsExport
@JsExport
abstract class JwtVerificationPolicy(override val name: String, override val description: String? = null) :
    VerificationPolicy(name, description) {
    @JvmBlocking
    @JvmAsync
    @JsPromise
    @JsExport.Ignore
    abstract suspend fun verify(credential: String, args: Any? = null, context: Map<String, Any>): Result<Any>

}
