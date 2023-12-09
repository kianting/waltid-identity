package id.walt.webwallet.config

import com.sksamuel.hoplite.Masked

data class PushConfig(val pushPublicKey: String, val pushPrivateKey: Masked, val pushSubject: String) : WalletConfig

