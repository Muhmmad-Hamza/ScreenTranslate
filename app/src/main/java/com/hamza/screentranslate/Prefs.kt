package com.hamza.screentranslate

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

object Prefs {
    const val NAME = "settings"
    const val KEY_TARGET = "target_lang"

    fun target(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_TARGET, TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH

    fun setTarget(ctx: Context, code: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TARGET, code).apply()
    }
}
