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
import android.os.SystemClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
                scheduleDailyMidnightSync()
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

        // 顯示目前已儲存的今日步數
        val prefs = getSharedPreferences("StepCounterPrefs", Context.MODE_PRIVATE)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (prefs.getString("last_date", "") == todayDate) {
            val savedSteps = prefs.getInt("today_steps", 0)
            tvTodaySteps.text = String.format("%,d", savedSteps)
        }

        // 短按歷史紀錄：呼叫系統分享匯出（CSV 格式）
        tvHistory.setOnClickListener {
            exportHistory()
        }

        // 長按歷史紀錄：跳出單筆刪除或全部清空選單
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
                    scheduleDailyMidnightSync()
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }
        } else {
            startListeningSensor()
            scheduleDailyMidnightSync()
        }
    }

    private fun startListeningSensor() {
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            tvStatus.text = "硬體感測器運作中（背景零耗電｜每日自動歸檔）"
        }
    }

    // 每天晚上 23:50 自動在背景結算當日步數
    private fun scheduleDailyMidnightSync() {
        val currentTime = Calendar.getInstance()
        val dueTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 50)
            set(Calendar.SECOND, 0)
            if (before(currentTime)) {
                add(Calendar.HOUR_OF_DAY, 24)
            }
        }
        val initialDelay = dueTime.timeInMillis - currentTime.timeInMillis

        val dailyWorkRequest = PeriodicWorkRequestBuilder<DailyStepWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "DailyStepSyncWork",
            ExistingPeriodicWorkPolicy.KEEP,
            dailyWorkRequest
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val totalStepsSinceBoot = it.values[0].toInt()
            val currentTodaySteps = StepStorageHelper.processAndSaveSteps(this, totalStepsSinceBoot)
            if (currentTodaySteps >= 0) {
                tvTodaySteps.text = String.format("%,d", currentTodaySteps)
            }
            loadAndDisplayHistory()
        }
    }

    private fun loadAndDisplayHistory() {
        val historyPrefs = getSharedPreferences("StepHistoryPrefs", Context.MODE_PRIVATE)
        val allEntries = historyPrefs.all

        if (allEntries.isEmpty()) {
            tvHistory.text = "尚無過去的歷史紀錄\n（今日步數將於每日睡前自動歸檔）"
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

    // 短按：呼叫系統分享匯出 CSV 格式
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

    // 長按：單筆刪除或全部清空選單
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

// 步數計算與重開機校正核心
object StepStorageHelper {
    fun processAndSaveSteps(context: Context, totalSinceBoot: Int): Int {
        val prefs = context.getSharedPreferences("StepCounterPrefs", Context.MODE_PRIVATE)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val savedDate = prefs.getString("last_date", "")
        var baselineSteps = prefs.getInt("baseline_steps", -1)
        var savedTodaySteps = prefs.getInt("today_steps", 0)
        val lastBootTime = prefs.getLong("last_boot_time", 0L)

        // 偵測手機是否曾經重新開機（容許 5 秒誤差）
        val currentBootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val hasRebooted = Math.abs(currentBootTime - lastBootTime) > 5000L

        if (savedDate != todayDate) {
            if (!savedDate.isNullOrEmpty()) {
                val historyPrefs = context.getSharedPreferences("StepHistoryPrefs", Context.MODE_PRIVATE)
                historyPrefs.edit().putInt(savedDate, savedTodaySteps).apply()
            }
            baselineSteps = if (hasRebooted) 0 else totalSinceBoot
            savedTodaySteps = totalSinceBoot - baselineSteps
            if (savedTodaySteps < 0) {
                baselineSteps = totalSinceBoot
                savedTodaySteps = 0
            }

            prefs.edit()
                .putString("last_date", todayDate)
                .putInt("baseline_steps", baselineSteps)
                .putInt("today_steps", savedTodaySteps)
                .putLong("last_boot_time", currentBootTime)
                .apply()
        } else if (hasRebooted || baselineSteps == -1 || totalSinceBoot < baselineSteps) {
            baselineSteps = totalSinceBoot - savedTodaySteps
            prefs.edit()
                .putInt("baseline_steps", baselineSteps)
                .putLong("last_boot_time", currentBootTime)
                .apply()
        }

        val currentTodaySteps = totalSinceBoot - baselineSteps
        if (currentTodaySteps >= 0) {
            prefs.edit().putInt("today_steps", currentTodaySteps).apply()
            return currentTodaySteps
        }
        return savedTodaySteps
    }
}

// 每日定時背景結算任務
class DailyStepWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return Result.success()

        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val totalStepsSinceBoot = it.values[0].toInt()
                    StepStorageHelper.processAndSaveSteps(applicationContext, totalStepsSinceBoot)
                }
                sensorManager.unregisterListener(this)
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        latch.await(3, TimeUnit.SECONDS)
        sensorManager.unregisterListener(listener)

        return Result.success()
    }
}
