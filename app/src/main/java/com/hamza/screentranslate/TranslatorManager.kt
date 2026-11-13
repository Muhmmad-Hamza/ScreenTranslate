package com.hamza.screentranslate

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * Korean -> target language, fully on-device (ML Kit).
 * Keeps a cache so that holding the button again shows results instantly.
 */
class TranslatorManager {

    private var translator: Translator? = null
    private var target: String? = null
    private val cache = ConcurrentHashMap<String, String>()

    fun ensure(targetLang: String) {
        if (targetLang == target && translator != null) return
        translator?.close()
        target = targetLang
        cache.clear()
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(targetLang)
                .build()
        )
        translator = t
        t.downloadModelIfNeeded() // no-op if already downloaded
    }

    fun cached(text: String): String? = cache[text]

    /** Calls [onDone] on the main thread with the translation. */
    fun translate(text: String, onDone: (String) -> Unit) {
        cache[text]?.let { onDone(it); return }
        val t = translator ?: return
        t.downloadModelIfNeeded()
            .onSuccessTask { t.translate(text) }
            .addOnSuccessListener { result ->
                if (t !== translator) return@addOnSuccessListener // language changed meanwhile
                if (cache.size > 3000) cache.clear()
                cache[text] = result
                onDone(result)
            }
    }

    fun close() {
        translator?.close()
        translator = null
    }
}
