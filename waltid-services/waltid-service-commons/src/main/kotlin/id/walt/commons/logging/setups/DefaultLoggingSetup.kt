package id.walt.commons.logging.setups

import id.walt.commons.logging.LogStringManager
import io.klogging.Level
import io.klogging.sending.STDERR
import io.klogging.sending.STDOUT

data object DefaultLoggingSetup : LoggingSetup("default", {
    sink("stdout", LogStringManager.selectedRenderString.renderString, STDOUT)
    sink("stderr", LogStringManager.selectedRenderString.renderString, STDERR)

    logging {
        fromMinLevel(Level.ERROR) {
            toSink("stderr")
        }
        fromMinLevel(Level.INFO) {
            toSink("stdout")
        }
    }
})
