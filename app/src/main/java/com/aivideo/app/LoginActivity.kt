package com.aivideo.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var etAddress: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var cbHttps: CheckBox
    private lateinit var cbRemember: CheckBox
    private lateinit var btnLogin: MaterialButton
    private lateinit var progressLogin: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forceLogin = intent.getBooleanExtra(EXTRA_FORCE_LOGIN, false)
        val existingAccount = SessionPrefs.getCurrentAccount(this)
        val existingToken = SessionPrefs.getToken(this)
        if (!forceLogin &&
            SessionPrefs.isRememberLogin(this) &&
            existingAccount != null &&
            existingToken.isNotBlank()
        ) {
            enterMultiView()
            return
        }

        setContentView(R.layout.activity_login)

        etAddress = findViewById(R.id.etAddress)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        cbHttps = findViewById(R.id.cbHttps)
        cbRemember = findViewById(R.id.cbRemember)
        btnLogin = findViewById(R.id.btnLogin)
        progressLogin = findViewById(R.id.progressLogin)

        val btnHistoryDropdown = findViewById<TextView>(R.id.btnHistoryDropdown)
        val btnLoginSettings = findViewById<TextView>(R.id.btnLoginSettings)

        if (existingAccount != null) {
            fillAccount(existingAccount)
        }
        cbRemember.isChecked = SessionPrefs.isRememberLogin(this)

        findViewById<View>(R.id.rowHttps).setOnClickListener {
            cbHttps.isChecked = !cbHttps.isChecked
        }
        findViewById<View>(R.id.rowRemember).setOnClickListener {
            cbRemember.isChecked = !cbRemember.isChecked
        }

        btnHistoryDropdown.setOnClickListener { showHistoryDialog() }
        btnLoginSettings.setOnClickListener { showSettingsDialog() }
        btnLogin.setOnClickListener { doLogin() }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doLogin()
                true
            } else {
                false
            }
        }
    }

    private fun fillAccount(account: SavedAccount) {
        etAddress.setText(account.displayHost())
        etUsername.setText(account.username)
        etPassword.setText(account.password)
        cbHttps.isChecked = account.useHttps
    }

    private fun showHistoryDialog() {
        val history = SessionPrefs.getHistory(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "暂无历史登录地址", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = history.map { acc ->
            val proto = if (acc.useHttps) "HTTPS" else "HTTP"
            "${acc.displayHost()}  (${acc.username} · $proto)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择历史服务器地址")
            .setItems(labels) { _, which ->
                fillAccount(history[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettingsDialog() {
        val items = arrayOf("预览时保持屏幕常亮", "允许自签名 HTTPS 证书")
        val checked = booleanArrayOf(
            SessionPrefs.isKeepScreenOn(this),
            SessionPrefs.isIgnoreSsl(this)
        )
        AlertDialog.Builder(this)
            .setTitle("连接设置")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                if (which == 0) SessionPrefs.setKeepScreenOn(this, isChecked)
                if (which == 1) SessionPrefs.setIgnoreSsl(this, isChecked)
            }
            .setPositiveButton("确定", null)
            .show()
    }

    private fun doLogin() {
        val rawAddress = etAddress.text.toString().trim()
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()

        if (rawAddress.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请输入账号和密码", Toast.LENGTH_SHORT).show()
            return
        }

        val useHttps = if (rawAddress.startsWith("https://", ignoreCase = true)) {
            true
        } else if (rawAddress.startsWith("http://", ignoreCase = true)) {
            false
        } else {
            cbHttps.isChecked
        }

        val account = SavedAccount(
            address = rawAddress,
            username = username,
            password = password,
            useHttps = useHttps
        )

        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = ApiClient.login(
                    baseUrl = account.normalizedBaseUrl(),
                    username = username,
                    password = password,
                    ignoreSsl = SessionPrefs.isIgnoreSsl(this@LoginActivity)
                )
                SessionPrefs.saveSession(
                    context = this@LoginActivity,
                    account = account,
                    token = result.token,
                    rememberLogin = cbRemember.isChecked
                )
                enterMultiView()
            } catch (e: Exception) {
                Toast.makeText(
                    this@LoginActivity,
                    e.message ?: "登录失败",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        progressLogin.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun enterMultiView() {
        startActivity(Intent(this, MultiViewActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_FORCE_LOGIN = "force_login"
    }
}
