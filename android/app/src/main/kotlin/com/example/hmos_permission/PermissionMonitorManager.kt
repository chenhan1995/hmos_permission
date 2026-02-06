package com.example.hmos_permission

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Telephony
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 权限监听管理器 - 单例模式
 * 负责实际的权限监听逻辑，可以被Plugin和Service共同使用
 */
object PermissionMonitorManager {
    
    interface EventListener {
        fun onEvent(event: Map<String, Any?>)
    }
    
    private val listeners = CopyOnWriteArrayList<EventListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    
    private var context: Context? = null
    private var appOpsManager: AppOpsManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var cameraManager: CameraManager? = null
    private var audioManager: AudioManager? = null
    private var usageStatsManager: UsageStatsManager? = null
    private var locationManager: LocationManager? = null
    
    private var isMonitoring = false
    private val opCallbacks = mutableListOf<Any>()
    private var cameraCallback: CameraManager.AvailabilityCallback? = null
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null
    private var smsObserver: ContentObserver? = null
    private var pollingRunnable: Runnable? = null
    
    private val activeCameraApps = mutableSetOf<String>()
    private val activeMicApps = mutableSetOf<String>()
    
    // 存储事件历史，供UI重新连接时获取
    private val eventHistory = mutableListOf<Map<String, Any?>>()
    private const val MAX_HISTORY_SIZE = 200
    
    fun initialize(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        
        appOpsManager = context?.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        telephonyManager = context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        cameraManager = context?.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        locationManager = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            usageStatsManager = context?.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        }
    }
    
    fun addListener(listener: EventListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    fun removeListener(listener: EventListener) {
        listeners.remove(listener)
    }
    
    fun getEventHistory(): List<Map<String, Any?>> {
        synchronized(eventHistory) {
            return eventHistory.toList()
        }
    }
    
    fun isMonitoring(): Boolean = isMonitoring
    
    fun startMonitoring(): Boolean {
        if (isMonitoring) return true
        if (context == null) return false
        
        isMonitoring = true
        
        startCameraMonitoring()
        startAudioRecordingMonitoring()
        startTelephonyMonitoring()
        startSmsMonitoring()
        startAppOpsMonitoring()
        startUsageStatsPolling()
        
        sendEvent(mapOf(
            "type" to "monitoring_started",
            "timestamp" to System.currentTimeMillis()
        ))
        
        return true
    }
    
    fun stopMonitoring() {
        if (!isMonitoring) return
        
        isMonitoring = false
        
        // Stop camera monitoring
        cameraCallback?.let {
            cameraManager?.unregisterAvailabilityCallback(it)
            cameraCallback = null
        }
        
        // Stop audio recording monitoring
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioRecordingCallback?.let {
                audioManager?.unregisterAudioRecordingCallback(it)
                audioRecordingCallback = null
            }
        }
        
        // Stop SMS monitoring
        smsObserver?.let {
            context?.contentResolver?.unregisterContentObserver(it)
            smsObserver = null
        }
        
        // Stop polling
        pollingRunnable?.let {
            mainHandler.removeCallbacks(it)
            pollingRunnable = null
        }
        
        // Stop AppOps monitoring
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            opCallbacks.forEach { callback ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    appOpsManager?.stopWatchingActive(callback as AppOpsManager.OnOpActiveChangedListener)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        opCallbacks.clear()
        
        sendEvent(mapOf(
            "type" to "monitoring_stopped",
            "timestamp" to System.currentTimeMillis()
        ))
    }
    
    private fun sendEvent(event: Map<String, Any?>) {
        // 添加到历史记录
        synchronized(eventHistory) {
            eventHistory.add(0, event)
            while (eventHistory.size > MAX_HISTORY_SIZE) {
                eventHistory.removeAt(eventHistory.size - 1)
            }
        }
        
        // 通知所有监听器
        mainHandler.post {
            listeners.forEach { it.onEvent(event) }
        }
    }
    
    private fun startCameraMonitoring() {
        cameraCallback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                super.onCameraUnavailable(cameraId)
                sendEvent(mapOf(
                    "type" to "camera_in_use",
                    "cameraId" to cameraId,
                    "timestamp" to System.currentTimeMillis()
                ))
                findCameraUsingApp()
            }
            
            override fun onCameraAvailable(cameraId: String) {
                super.onCameraAvailable(cameraId)
                sendEvent(mapOf(
                    "type" to "camera_available",
                    "cameraId" to cameraId,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
        cameraManager?.registerAvailabilityCallback(cameraCallback!!, mainHandler)
    }
    
    private fun findCameraUsingApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            executor.execute {
                try {
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - 5000
                    
                    val usageEvents = usageStatsManager?.queryEvents(startTime, endTime)
                    var lastForegroundApp: String? = null
                    
                    while (usageEvents?.hasNextEvent() == true) {
                        val event = UsageEvents.Event()
                        usageEvents.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                            lastForegroundApp = event.packageName
                        }
                    }
                    
                    lastForegroundApp?.let { pkg ->
                        val appName = getAppName(pkg)
                        sendEvent(mapOf(
                            "type" to "permission_active_changed",
                            "operation" to "CAMERA",
                            "operationName" to "相机",
                            "packageName" to pkg,
                            "appName" to appName,
                            "active" to true,
                            "timestamp" to System.currentTimeMillis()
                        ))
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
    
    private fun startAudioRecordingMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioRecordingCallback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    super.onRecordingConfigChanged(configs)
                    
                    if (configs.isNotEmpty()) {
                        sendEvent(mapOf(
                            "type" to "audio_recording_started",
                            "recordingCount" to configs.size,
                            "timestamp" to System.currentTimeMillis()
                        ))
                        findMicUsingApp()
                    } else {
                        sendEvent(mapOf(
                            "type" to "audio_recording_stopped",
                            "timestamp" to System.currentTimeMillis()
                        ))
                    }
                }
            }
            audioManager?.registerAudioRecordingCallback(audioRecordingCallback!!, mainHandler)
        }
    }
    
    private fun findMicUsingApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            executor.execute {
                try {
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - 5000
                    
                    val usageEvents = usageStatsManager?.queryEvents(startTime, endTime)
                    var lastForegroundApp: String? = null
                    
                    while (usageEvents?.hasNextEvent() == true) {
                        val event = UsageEvents.Event()
                        usageEvents.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                            lastForegroundApp = event.packageName
                        }
                    }
                    
                    lastForegroundApp?.let { pkg ->
                        val appName = getAppName(pkg)
                        sendEvent(mapOf(
                            "type" to "permission_active_changed",
                            "operation" to "RECORD_AUDIO",
                            "operationName" to "麦克风",
                            "packageName" to pkg,
                            "appName" to appName,
                            "active" to true,
                            "timestamp" to System.currentTimeMillis()
                        ))
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
    
    private fun startTelephonyMonitoring() {
        val ctx = context ?: return
        
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) 
            != PackageManager.PERMISSION_GRANTED) {
            sendEvent(mapOf(
                "type" to "warning",
                "message" to "缺少READ_PHONE_STATE权限，无法监听通话状态",
                "timestamp" to System.currentTimeMillis()
            ))
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallStateChanged(state)
                    }
                }
                telephonyManager?.registerTelephonyCallback(executor, callback)
                opCallbacks.add(callback)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallStateChanged(state)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                opCallbacks.add(listener)
            }
        } catch (e: Exception) {
            sendEvent(mapOf(
                "type" to "error",
                "message" to "启动通话监听失败: ${e.message}",
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }
    
    private fun handleCallStateChanged(state: Int) {
        val stateName = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> "idle"
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            else -> "unknown"
        }
        
        sendEvent(mapOf(
            "type" to "call_state_changed",
            "state" to stateName,
            "stateCode" to state,
            "timestamp" to System.currentTimeMillis()
        ))
    }
    
    private fun startSmsMonitoring() {
        val ctx = context ?: return
        
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) 
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        try {
            smsObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    sendEvent(mapOf(
                        "type" to "sms_activity",
                        "uri" to uri?.toString(),
                        "timestamp" to System.currentTimeMillis()
                    ))
                }
            }
            
            ctx.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                smsObserver!!
            )
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun startAppOpsMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val operations = listOf(
                AppOpsManager.OPSTR_FINE_LOCATION,
                AppOpsManager.OPSTR_COARSE_LOCATION,
                AppOpsManager.OPSTR_CAMERA,
                AppOpsManager.OPSTR_RECORD_AUDIO
            )
            
            operations.forEach { op ->
                try {
                    val callback = AppOpsManager.OnOpActiveChangedListener { opStr, uid, packageName, active ->
                        val appName = getAppName(packageName)
                        sendEvent(mapOf(
                            "type" to "permission_active_changed",
                            "operation" to opStr,
                            "operationName" to getOperationDisplayName(opStr),
                            "uid" to uid,
                            "packageName" to packageName,
                            "appName" to appName,
                            "active" to active,
                            "timestamp" to System.currentTimeMillis()
                        ))
                    }
                    appOpsManager?.startWatchingActive(arrayOf(op), executor, callback)
                    opCallbacks.add(callback)
                } catch (e: Exception) {
                    // Some operations may not be watchable
                }
            }
        }
    }
    
    private fun startUsageStatsPolling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return
        if (!hasUsageStatsPermission()) {
            sendEvent(mapOf(
                "type" to "warning",
                "message" to "缺少使用情况访问权限，无法监听其他应用的权限使用",
                "timestamp" to System.currentTimeMillis()
            ))
            return
        }
        
        var lastForegroundPackage: String? = null
        
        pollingRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                
                try {
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - 10000
                    
                    val usageEvents = usageStatsManager?.queryEvents(startTime, endTime)
                    var currentForeground: String? = null
                    
                    while (usageEvents?.hasNextEvent() == true) {
                        val event = UsageEvents.Event()
                        usageEvents.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                            currentForeground = event.packageName
                        }
                    }
                    
                    if (currentForeground != null && currentForeground != lastForegroundPackage) {
                        lastForegroundPackage = currentForeground
                        val appName = getAppName(currentForeground)
                        sendEvent(mapOf(
                            "type" to "app_foreground_changed",
                            "packageName" to currentForeground,
                            "appName" to appName,
                            "timestamp" to System.currentTimeMillis()
                        ))
                    }
                } catch (e: Exception) {
                    // Ignore polling errors
                }
                
                mainHandler.postDelayed(this, 3000)
            }
        }
        
        mainHandler.postDelayed(pollingRunnable!!, 1000)
    }
    
    fun hasUsageStatsPermission(): Boolean {
        val ctx = context ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return true
        
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
    
    fun openUsageStatsSettings() {
        val ctx = context ?: return
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
    
    private fun getAppName(packageName: String): String {
        return try {
            val ctx = context ?: return packageName
            val pm = ctx.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
    
    private fun getOperationDisplayName(operation: String): String {
        return when (operation) {
            AppOpsManager.OPSTR_CAMERA -> "相机"
            AppOpsManager.OPSTR_RECORD_AUDIO -> "麦克风"
            AppOpsManager.OPSTR_FINE_LOCATION -> "精确定位"
            AppOpsManager.OPSTR_COARSE_LOCATION -> "粗略定位"
            AppOpsManager.OPSTR_READ_SMS -> "读取短信"
            AppOpsManager.OPSTR_RECEIVE_SMS -> "接收短信"
            AppOpsManager.OPSTR_READ_PHONE_STATE -> "读取手机状态"
            else -> operation
        }
    }
    
    fun getSystemInfo(): Map<String, Any> {
        val isHarmonyOS = HarmonyOSAdapter.isHarmonyOS()
        return mapOf(
            "isHarmonyOS" to isHarmonyOS,
            "harmonyVersion" to (if (isHarmonyOS) HarmonyOSAdapter.getHarmonyVersion() else ""),
            "sdkVersion" to Build.VERSION.SDK_INT,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "osName" to if (isHarmonyOS) "HarmonyOS" else "Android ${Build.VERSION.RELEASE}"
        )
    }
}
