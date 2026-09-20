package com.drcom.autologin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.drcom.autologin.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private var keepAliveRunning = false
    private var lastLogText: String? = null

    private val refreshLoop = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshLog(false)
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()

        val cfg = Prefs.load(this)
        loadConfigToUi(cfg)
        keepAliveRunning = cfg.keepAlive

        binding.btnSave.setOnClickListener { applyConfig(true) }

        binding.btnLoginNow.setOnClickListener {
            if (applyConfig(false)) {
                Scheduler.runNow(this, Scheduler.REASON_MANUAL)
                toast(getString(R.string.toast_login_queued))
            }
        }

        binding.btnBattery.setOnClickListener { openBatterySettings() }

        binding.btnClearLog.setOnClickListener {
            LogStore.clear(this)
            lastLogText = null
            refreshLog(true)
            toast(getString(R.string.toast_log_cleared))
        }

        binding.btnRefreshLog.setOnClickListener { refreshLog(true) }

        requestBasePermissions()

        if (cfg.autoCheck) Scheduler.ensurePeriodic(this)
        if (cfg.keepAlive) startKeepAlive()

        refreshStatus()
        refreshLog(true)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshLoop)
    }

    // ------------------------------------------------------------------ 下拉框

    private fun setupSpinners() {
        val suffixAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Config.SUFFIX_LABELS
        )
        suffixAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spSuffix.adapter = suffixAdapter

        val intervalLabels = Config.INTERVAL_OPTIONS.map { getString(R.string.interval_suffix, it) }
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalLabels)
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spInterval.adapter = intervalAdapter
    }

    // ------------------------------------------------------------------ UI 读写

    private fun loadConfigToUi(cfg: Config) {
        binding.etHost.setText(cfg.host)
        binding.etPort.setText(cfg.port.toString())
        binding.etAccount.setText(cfg.account)
        binding.etPassword.setText(cfg.password)
        binding.etSsidFilter.setText(cfg.ssidFilter)
        binding.swAutoCheck.isChecked = cfg.autoCheck
        binding.swKeepAlive.isChecked = cfg.keepAlive

        val suffixIndex = Config.SUFFIX_VALUES.indexOf(cfg.suffix)
        binding.spSuffix.setSelection(if (suffixIndex >= 0) suffixIndex else 0)

        val intervalIndex = Config.INTERVAL_OPTIONS.indexOf(cfg.intervalMinutes)
        binding.spInterval.setSelection(
            if (intervalIndex >= 0) intervalIndex else Config.INTERVAL_OPTIONS.indexOf(Config.DEFAULT.intervalMinutes)
        )
    }

    /** 校验 + 保存 + 应用调度；返回是否保存成功。 */
    private fun applyConfig(showToast: Boolean): Boolean {
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: Config.DEFAULT.port
        if (port < 1 || port > 65535) {
            toast(getString(R.string.toast_bad_port))
            return false
        }
        val account = binding.etAccount.text.toString().trim()
        if (account.isEmpty()) {
            toast(getString(R.string.toast_need_account))
            return false
        }

        val cfg = Config(
            host = binding.etHost.text.toString().trim().ifEmpty { Config.DEFAULT.host },
            port = port,
            account = account,
            suffix = Config.SUFFIX_VALUES.getOrElse(binding.spSuffix.selectedItemPosition) { "" },
            password = binding.etPassword.text.toString(),
            autoCheck = binding.swAutoCheck.isChecked,
            intervalMinutes = Config.INTERVAL_OPTIONS
                .getOrElse(binding.spInterval.selectedItemPosition) { Config.DEFAULT.intervalMinutes },
            ssidFilter = binding.etSsidFilter.text.toString().trim(),
            keepAlive = binding.swKeepAlive.isChecked
        )
        Prefs.save(this, cfg)

        if (cfg.autoCheck) Scheduler.ensurePeriodic(this) else Scheduler.cancel(this)

        if (cfg.keepAlive) {
            if (!keepAliveRunning) startKeepAlive()
        } else {
            if (keepAliveRunning) stopKeepAlive()
        }
        keepAliveRunning = cfg.keepAlive

        if (cfg.ssidFilter.isNotEmpty()) requestLocationPermission()

        if (showToast) toast(getString(R.string.toast_saved))
        return true
    }

    // -------------------------------------------------------------------- 状态

    private fun refreshStatus() {
        val st = Prefs.readStatus(this)

        val networkText = when (st.networkReachable) {
            true -> getString(R.string.state_reachable)
            false -> getString(R.string.state_unreachable)
            null -> getString(R.string.state_unknown)
        }
        binding.tvNetwork.text = getString(R.string.status_network_label, networkText)

        val onlineText = when (st.online) {
            true -> getString(R.string.state_online)
            false -> getString(R.string.state_offline)
            null -> getString(R.string.state_unknown)
        }
        binding.tvOnline.text = getString(R.string.status_online_label, onlineText)
        binding.tvOnline.setTextColor(
            ContextCompat.getColor(
                this,
                when (st.online) {
                    true -> R.color.status_online
                    false -> R.color.status_offline
                    null -> R.color.status_unknown
                }
            )
        )

        val lastCheck = if (st.lastCheckAt > 0L) {
            timeFormat.format(Date(st.lastCheckAt))
        } else {
            getString(R.string.value_none)
        }
        binding.tvLastCheck.text = getString(R.string.status_last_check_label, lastCheck)
        binding.tvLastError.text = getString(
            R.string.status_last_error_label,
            st.lastError ?: getString(R.string.value_none)
        )
        binding.tvSsid.text = getString(
            R.string.status_ssid_label,
            LoginEngine.currentSsid(this) ?: getString(R.string.value_none)
        )
    }

    private fun refreshLog(force: Boolean) {
        val raw = LogStore.read(this, 100)
        val shown = if (raw.isBlank()) getString(R.string.log_empty) else raw
        if (!force && shown == lastLogText) return
        lastLogText = shown
        binding.tvLog.text = shown
        val container = binding.tvLog.parent as? ScrollView
        container?.post { container.fullScroll(View.FOCUS_DOWN) }
    }

    // -------------------------------------------------------------------- 保活

    private fun startKeepAlive() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        } catch (t: Throwable) {
            LogStore.log(this, "WARN", "启动保活服务失败: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun stopKeepAlive() {
        try {
            stopService(Intent(this, KeepAliveService::class.java))
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast(getString(R.string.toast_battery_unavailable))
            }
        }
    }

    // -------------------------------------------------------------------- 权限

    private fun requestBasePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_BASE)
        }
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQ_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION &&
            grantResults.isNotEmpty() &&
            grantResults[0] != PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.toast_permission_denied))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 2000L
        const val REQ_BASE = 1001
        const val REQ_LOCATION = 1002
    }
}
