package com.bosh.substore

import android.content.Context

object Prefs {
    private const val FILE = "substore_prefs"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_EXTRACTED_VER = "extracted_runtime_ver"
    private const val KEY_DISCLAIMER = "disclaimer_accepted"

    /** 免责声明是否已同意（持久化，只在第一次运行时弹）。 */
    fun disclaimerAccepted(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DISCLAIMER, false)

    fun setDisclaimerAccepted(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_DISCLAIMER, true).apply()
    }

    fun autostart(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART, true)

    fun setAutostart(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTOSTART, v).apply()
    }

    fun extractedVer(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_EXTRACTED_VER, null)

    fun setExtractedVer(ctx: Context, v: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_EXTRACTED_VER, v).apply()
    }
}
