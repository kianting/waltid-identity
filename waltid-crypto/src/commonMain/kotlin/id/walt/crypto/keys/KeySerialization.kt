package id.walt.crypto.keys

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import love.forte.plugin.suspendtrans.annotation.JsPromise
import love.forte.plugin.suspendtrans.annotation.JvmAsync
import love.forte.plugin.suspendtrans.annotation.JvmBlocking
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@ExperimentalJsExport
@JsExport
object KeySerialization {

    private val keySerializationModule = SerializersModule {
        polymorphic(Key::class) {
            subclass(LocalKey::class)
            subclass(TSEKey::class)
        }
    }

    private val keySerializationJson = Json {
        serializersModule =
            keySerializationModule
    }

    fun serializeKey(key: Key): String = keySerializationJson.encodeToString(key)
    @JvmBlocking
    @JvmAsync
    @JsPromise
    @JsExport.Ignore
    suspend fun deserializeKey(json: String): Result<Key> = runCatching { keySerializationJson.decodeFromString<Key>(json).apply { init() } }
    @JvmBlocking
    @JvmAsync
    @JsPromise
    @JsExport.Ignore
    suspend fun deserializeKey(json: JsonObject): Result<Key> = runCatching { keySerializationJson.decodeFromJsonElement<Key>(json).apply { init() } }
}
