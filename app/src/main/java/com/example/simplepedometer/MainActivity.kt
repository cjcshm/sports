package com.example.simplepedometer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

        // 先顯示目前已儲存的今日步數（避免剛打開畫面顯示 0）
        val prefs = getSharedPreferences("StepCounterPrefs", Context.MODE_PRIVATE)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (prefs.getString("last_date", "") == todayDate) {
            val savedSteps = prefs.getInt("today_steps", 0)
            tvTodaySteps.text = String.format("%,d", savedSteps)
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

    // 設定每天晚上 23:50 自動在背景結算當日步數（執行完立刻休眠，不常駐）
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
        tvHistory.text = sb.toString().trim()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// 共用的步數計算與重開機校正核心（前台與背景排程共用）
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
            // 跨日歸檔：把前一天的最終步數寫入歷史紀錄
            if (!savedDate.isNullOrEmpty()) {
                val historyPrefs = context.getSharedPreferences("StepHistoryPrefs", Context.MODE_PRIVATE)
                historyPrefs.edit().putInt(savedDate, savedTodaySteps).apply()
            }
            // 新的一天起算
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
            // 同一天但手機曾重開機：保留關機前累積的步數，重新校正基準點
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

// 每日定時背景結算任務（喚醒 1 秒讀取硬體步數並存檔，完成後立刻休眠）
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
        // 最多等待 3 秒取得硬體讀數，取得後立刻釋放資源休眠
        latch.await(3, TimeUnit.SECONDS)
        sensorManager.unregisterListener(listener)

        return Result.success()
    }
}
