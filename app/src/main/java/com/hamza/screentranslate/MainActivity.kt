package com.hamza.screentranslate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        layout.addView(TextView(this).apply {
            textSize = 15f
            text = "1. Choose the language you want Korean translated into.\n" +
                "2. Turn on \"Screen Translate button\" in Accessibility settings.\n" +
                "3. A blue button appears over every app:\n" +
                "   • Press & hold → screen in your language, release → Korean\n" +
                "   • Tap → stays translated until you tap again\n" +
                "   • Drag → move the button\n"
        })

        layout.addView(TextView(this).apply { text = "Translate Korean into:"; textSize = 16f })

        val codes = TranslateLanguage.getAllLanguages()
            .filter { it != TranslateLanguage.KOREAN }
            .sortedBy { Locale(it).displayLanguage }
        val names = codes.map { "${Locale(it).displayLanguage} ($it)" }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(codes.indexOf(Prefs.target(this@MainActivity)).coerceAtLeast(0))
        }
        layout.addView(spinner)

        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad) }
        layout.addView(status)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setTarget(this@MainActivity, codes[pos])
                downloadModel(codes[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        layout.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        setContentView(layout)
    }

    private fun downloadModel(target: String) {
        status.text = "Downloading language model (needs internet once)…"
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(target)
                .build()
        )
        t.downloadModelIfNeeded()
            .addOnSuccessListener { status.text = "✓ Ready — works offline now"; t.close() }
            .addOnFailureListener { status.text = "Download failed: ${it.message}"; t.close() }
    }
}
