package id.walt.credentials.vc.vcs

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@ExperimentalJsExport
@JsExport
sealed interface CredentialDataModel {
    fun encodeToJsonObject(): JsonObject
    @JsExport.Ignore
    companion object {
        internal val w3cJson = Json {
            @OptIn(ExperimentalSerializationApi::class)
            explicitNulls = false
        }
    }
}
