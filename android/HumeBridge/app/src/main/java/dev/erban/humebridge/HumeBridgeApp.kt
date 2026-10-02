package dev.erban.humebridge

import android.app.Application
import dev.erban.humebridge.data.HumeBridgeDatabase

class HumeBridgeApp : Application() {
    val database: HumeBridgeDatabase by lazy { HumeBridgeDatabase.get(this) }
}
