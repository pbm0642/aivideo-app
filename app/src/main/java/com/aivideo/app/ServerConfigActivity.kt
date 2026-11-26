package com.aivideo.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class ServerConfigActivity : AppCompatActivity() {

    private lateinit var etServerUrl: TextInputEditText
    private lateinit var cbKeepScreenOn: CheckBox
    private lateinit var cbIgnoreSsl: CheckBox
    private lateinit var llHistoryContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forceConfig = intent.getBooleanExtra(EXTRA_FORCE_CONFIG, false)
        val savedUrl = ServerPrefs.getCurrentUrl(this)
        if (!forceConfig && savedUrl.isNotBlank()) {
            openWebViewer(savedUrl)
            return
        }

        setContentView(R.layout.activity_server_config)

        etServerUrl = findViewById(R.id.etServerUrl)
        cbKeepScreenOn = findViewById(R.id.cbKeepScreenOn)
        cbIgnoreSsl = findViewById(R.id.cbIgnoreSsl)
        llHistoryContainer = findViewById(R.id.llHistoryContainer)
        val btnConnect = findViewById<MaterialButton>(R.id.btnConnect)

        if (savedUrl.isNotBlank()) {
            etServerUrl.setText(savedUrl)
        }
        cbKeepScreenOn.isChecked = ServerPrefs.isKeepScreenOn(this)
        cbIgnoreSsl.isChecked = ServerPrefs.isIgnoreSsl(this)

        btnConnect.setOnClickListener { submitUrl() }
        etServerUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitUrl()
                true
            } else {
                false
            }
        }

        renderHistory()
    }

    private fun submitUrl() {
        val raw = etServerUrl.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            Toast.makeText(this, R.string.err_empty_url, Toast.LENGTH_SHORT).show()
            return
        }

        val normalized = if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            raw
        } else {
            "http://$raw"
        }

        ServerPrefs.saveServer(
            context = this,
            url = normalized,
            keepScreenOn = cbKeepScreenOn.isChecked,
            ignoreSsl = cbIgnoreSsl.isChecked
        )
        openWebViewer(normalized)
    }

    private fun openWebViewer(url: String) {
        val intent = Intent(this, WebViewerActivity::class.java).apply {
            putExtra(WebViewerActivity.EXTRA_URL, url)
        }
        startActivity(intent)
        finish()
    }

    private fun renderHistory() {
        llHistoryContainer.removeAllViews()
        val history = ServerPrefs.getHistory(this)
        if (history.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = getString(R.string.no_recent_servers)
                setTextColor(Color.parseColor("#64748B"))
                textSize = 13f
                setPadding(0, 12, 0, 12)
            }
            llHistoryContainer.addView(emptyTv)
            return
        }

        history.forEach { url ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#1E293B"))
                setPadding(28, 20, 20, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
            }

            val tvUrl = TextView(this).apply {
                text = url
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    etServerUrl.setText(url)
                    submitUrl()
                }
            }

            val tvDelete = TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 15f
                setPadding(24, 8, 12, 8)
                setOnClickListener {
                    ServerPrefs.removeFromHistory(this@ServerConfigActivity, url)
                    renderHistory()
                }
            }

            row.addView(tvUrl)
            row.addView(tvDelete)
            llHistoryContainer.addView(row)
        }
    }

    companion object {
        const val EXTRA_FORCE_CONFIG = "force_config"
    }
}
