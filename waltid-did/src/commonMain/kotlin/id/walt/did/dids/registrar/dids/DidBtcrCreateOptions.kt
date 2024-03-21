package id.walt.did.dids.registrar.dids

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@OptIn(ExperimentalJsExport::class)
@JsExport
class DidBtcrCreateOptions(chain: String) : DidCreateOptions(
    method = "btcr",
    options = options("chain" to chain)
)
