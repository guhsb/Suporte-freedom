package com.freedom.remoteaccess

import android.content.Context

/**
 * Guarda localmente o que veio do QR de pareamento (URL/key do Supabase e
 * o deviceId), para não precisar escanear de novo a cada abertura do app.
 */
object DeviceConfig {
    private const val PREFS = "pareamento"

    fun save(context: Context, supabaseUrl: String, supabaseKey: String, deviceId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("supabaseUrl", supabaseUrl)
            .putString("supabaseKey", supabaseKey)
            .putString("deviceId", deviceId)
            .apply()
    }

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("deviceId")

    fun supabaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("supabaseUrl", "")!!

    fun supabaseKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("supabaseKey", "")!!

    fun deviceId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("deviceId", "")!!
}
