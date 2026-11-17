package com.example.simplepedometer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null

    private lateinit var tvTodaySteps: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvHistory: TextView

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startListeningSensor()
            } else {
                tvStatus.text = "需要「體能活動」權限才能讀取步數，請至手機設定開啟。"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTodaySteps = findViewById(R.id.tv_today_steps)
        tvStatus = findViewById(R.id.tv_status)
        tvHistory = findViewById(R.id.tv_history)

        // 短按歷史紀錄：呼叫系統分享匯出（零檔案佔用）
        tvHistory.setOnClickListener {
            exportHistory()
        }

        // 長按歷史紀錄：跳出刪除單筆或全部清空選單
        tvHistory.setOnLongClickListener {
            showDeleteDialog()
            true
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor == null) {
            tvStatus.text = "此手機硬體不支援內建計步感測器"
        }

        loadAndDisplayHistory()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndStart()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun checkPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startListeningSensor()
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }
        } else {
            startListeningSensor()
        }
    }

    private fun startListeningSensor() {
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            tvStatus.text = "硬體感測器運作中（背景零耗電）"
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val totalStepsSinceBoot = it.values[0].toInt()
            calculateAndSaveSteps(totalStepsSinceBoot)
        }
    }

    private fun calculateAndSaveSteps(totalSinceBoot: Int) {
        val prefs = getSharedPreferences("StepCounterPrefs", Context.MODE_PRIVATE)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val savedDate = prefs.getString("last_date", "")
        var baselineSteps = prefs.getInt("baseline_steps", -1)
        var savedTodaySteps = prefs.getInt("today_steps", 0)

        if (savedDate != todayDate) {
            if (!savedDate.isNullOrEmpty()) {
                saveToHistory(savedDate, savedTodaySteps)
            }
            baselineSteps = totalSinceBoot
            savedTodaySteps = 0
            prefs.edit()
                .putString("last_date", todayDate)
                .putInt("baseline_steps", baselineSteps)
                .putInt("today_steps", 0)
                .apply()
        } else if (baselineSteps == -1 || totalSinceBoot < baselineSteps) {
            baselineSteps = totalSinceBoot - savedTodaySteps
            if (baselineSteps < 0) baselineSteps = 0
            prefs.edit().putInt("baseline_steps", baselineSteps).apply()
        }

        val currentTodaySteps = totalSinceBoot - baselineSteps
        if (currentTodaySteps >= 0) {
            prefs.edit().putInt("today_steps", currentTodaySteps).apply()
            tvTodaySteps.text = String.format("%,d", currentTodaySteps)
        }

        loadAndDisplayHistory()
    }

    private fun saveToHistory(date: String, steps: Int) {
        val historyPrefs = getSharedPreferences("StepHistoryPrefs", Context.MODE_PRIVATE)
        historyPrefs.edit().putInt(date, steps).apply()
    }

    private fun loadAndDisplayHistory() {
        val historyPrefs = getSharedPreferences("StepHistoryPrefs", Context.MODE_PRIVATE)
        val allEntries = historyPrefs.all

        if (allEntries.isEmpty()) {
            tvHistory.text = "尚無過去的歷史紀錄\n（今日步數將於明日自動歸檔）"
            return
        }

        val sortedDates = allEntries.keys.sortedDescending()
        val sb = StringBuilder()
        for (date in sortedDates) {
            val steps = allEntries[date] as? Int ?: 0
            sb.append("$date  ：  ${String.format("%,d", steps)} 步\n")
        }
        sb.append("\n（點一下匯出分享｜長按可刪除紀錄）")
        tvHistory.text = sb.toString().trim()
    }

    // 匯出歷史紀錄（呼叫系統分享選單）
    private fun exportHistory() {
        val historyPrefs = getSharedPreferences("StepHistoryPrefs", Context.MODE_PRIVATE)
        val allEntries = historyPrefs.all
        if (allEntries.isEmpty()) {
            Toast.makeText(this, "目前沒有可匯出的歷史紀錄", Toast.LENGTH_SHORT).show()
            return
        }

        val sortedDates = allEntries.keys.sortedDescending()
        val sb = StringBuilder("日期,步數\n")
        for (date in sortedDates) {
            val steps = allEntries[date] as? Int ?: 0
            sb.append("$date,$steps\n")
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "計步器歷史紀錄")
            putExtra(Intent.EXTRA_TEXT, sb.toString().trim())
        }
        startActivity(Intent.createChooser(sendIntent, "匯出歷史紀錄至..."))
    }

    // 刪除單筆或全部歷史紀錄
    private fun showDeleteDialog() {
        val historyPrefs = getSharedPreferences("StepHistoryPrefs", Context.MODE_PRIVATE)
        val allEntries = historyPrefs.all
        if (allEntries.isEmpty()) {
            Toast.makeText(this, "目前沒有可刪除的歷史紀錄", Toast.LENGTH_SHORT).show()
            return
        }

        val sortedDates = allEntries.keys.sortedDescending()
        val items = sortedDates.map { date ->
            val steps = allEntries[date] as? Int ?: 0
            "$date  (${String.format("%,d", steps)} 步)"
        }.toMutableList()
        items.add("全部清空所有歷史紀錄")

        AlertDialog.Builder(this)
            .setTitle("選擇要刪除的紀錄")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == items.lastIndex) {
                    // 二次確認全部清空
                    AlertDialog.Builder(this)
                        .setTitle("確認清空")
                        .setMessage("確定要刪除所有歷史紀錄嗎？此動作無法復原。")
                        .setPositiveButton("確定清空") { _, _ ->
                            historyPrefs.edit().clear().apply()
                            loadAndDisplayHistory()
                            Toast.makeText(this, "已清空所有歷史紀錄", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    // 刪除單筆特定日期
                    val targetDate = sortedDates[which]
                    historyPrefs.edit().remove(targetDate).apply()
                    loadAndDisplayHistory()
                    Toast.makeText(this, "已刪除 $targetDate 的紀錄", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
