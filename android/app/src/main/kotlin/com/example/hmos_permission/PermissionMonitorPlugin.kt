package com.example.hmos_permission

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.location.LocationManager
import androidx.annotation.RequiresApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class PermissionMonitorPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var context: Context
    private var eventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    
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
    
    // Track active camera/mic usage
    private val activeCameraApps = mutableSetOf<String>()
    private val activeMicApps = mutableSetOf<String>()
    
    companion object {
        const val METHOD_CHANNEL = "com.example.hmos_permission/method"
        const val EVENT_CHANNEL = "com.example.hmos_permission/events"
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        
        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(this)
        
        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })
        
        appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        stopMonitoring()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startMonitoring" -> {
                startMonitoring()
                result.success(true)
            }
            "stopMonitoring" -> {
                stopMonitoring()
                result.success(true)
            }
            "getSystemInfo" -> {
                result.success(getSystemInfo())
            }
            "checkPermissionStatus" -> {
                val permissionType = call.argument<String>("type")
                result.success(checkPermissionStatus(permissionType))
            }
            "getActivePermissionUsage" -> {
                result.success(getActivePermissionUsage())
            }
            "isHarmonyOS" -> {
                result.success(isHarmonyOS())
            }
            "hasUsageStatsPermission" -> {
                result.success(hasUsageStatsPermission())
            }
            "openUsageStatsSettings" -> {
                openUsageStatsSettings()
                result.success(true)
            }
            "getRecentPermissionUsage" -> {
                val minutes = call.argument<Int>("minutes") ?: 5
                result.success(getRecentPermissionUsage(minutes))
            }
            else -> result.notImplemented()
        }
    }
    
    private fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        
        // Check usage stats permission
        val hasUsageStats = hasUsageStatsPermission()
        
        sendEvent(mapOf(
            "type" to "monitoring_started",
            "timestamp" to System.currentTimeMillis(),
            "apiLevel" to Build.VERSION.SDK_INT,
            "hasUsageStatsPermission" to hasUsageStats
        ))
        
        if (!hasUsageStats) {
            sendEvent(mapOf(
                "type" to "warning",
                "message" to "需要授予\"使用情况访问权限\"才能监听其他应用的权限使用",
                "action" to "openUsageStatsSettings",
                "timestamp" to System.currentTimeMillis()
            ))
        }
        
        // Start camera availability monitoring
        startCameraMonitoring()
        
        // Start audio recording monitoring
        startAudioRecordingMonitoring()
        
        // Start telephony monitoring
        startTelephonyMonitoring()
        
        // Start SMS monitoring
        startSmsMonitoring()
        
        // Start AppOps monitoring for API 29+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startAppOpsMonitoring()
        }
        
        // Start polling for usage stats
        startUsageStatsPolling()
    }
    
    private fun startCameraMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cameraCallback = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    // Camera became available (was released)
                    sendEvent(mapOf(
                        "type" to "camera_available",
                        "cameraId" to cameraId,
                        "message" to "相机 $cameraId 已释放",
                        "timestamp" to System.currentTimeMillis()
                    ))
                }

                override fun onCameraUnavailable(cameraId: String) {
                    // Camera is being used
                    sendEvent(mapOf(
                        "type" to "camera_in_use",
                        "cameraId" to cameraId,
                        "message" to "相机 $cameraId 正在被使用",
                        "timestamp" to System.currentTimeMillis()
                    ))
                    
                    // Try to find which app is using it
                    findCameraUsingApp()
                }
            }
            cameraManager?.registerAvailabilityCallback(cameraCallback!!, mainHandler)
            opCallbacks.add(cameraCallback!!)
        }
    }
    
    private fun findCameraUsingApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasUsageStatsPermission()) {
            executor.execute {
                try {
                    val packages = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (app in packages) {
                        try {
                            val isActive = appOpsManager?.isOpActive(
                                AppOpsManager.OPSTR_CAMERA,
                                app.uid,
                                app.packageName
                            ) ?: false
                            
                            if (isActive && !activeCameraApps.contains(app.packageName)) {
                                activeCameraApps.add(app.packageName)
                                val appName = getAppName(app.packageName)
                                sendEvent(mapOf(
                                    "type" to "permission_active_changed",
                                    "operation" to AppOpsManager.OPSTR_CAMERA,
                                    "operationName" to "相机",
                                    "packageName" to app.packageName,
                                    "appName" to appName,
                                    "uid" to app.uid,
                                    "active" to true,
                                    "timestamp" to System.currentTimeMillis()
                                ))
                            }
                        } catch (e: Exception) {
                            // Skip
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.N)
    private fun startAudioRecordingMonitoring() {
        audioRecordingCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                if (configs.isNotEmpty()) {
                    for (config in configs) {
                        val clientUid = config.clientAudioSessionId
                        sendEvent(mapOf(
                            "type" to "audio_recording_started",
                            "sessionId" to clientUid,
                            "message" to "检测到麦克风正在录音",
                            "timestamp" to System.currentTimeMillis()
                        ))
                    }
                    // Try to find which app is recording
                    findMicUsingApp()
                } else {
                    sendEvent(mapOf(
                        "type" to "audio_recording_stopped",
                        "message" to "麦克风录音已停止",
                        "timestamp" to System.currentTimeMillis()
                    ))
                    activeMicApps.clear()
                }
            }
        }
        audioManager?.registerAudioRecordingCallback(audioRecordingCallback!!, mainHandler)
        opCallbacks.add(audioRecordingCallback!!)
    }
    
    private fun findMicUsingApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasUsageStatsPermission()) {
            executor.execute {
                try {
                    val packages = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (app in packages) {
                        try {
                            val isActive = appOpsManager?.isOpActive(
                                AppOpsManager.OPSTR_RECORD_AUDIO,
                                app.uid,
                                app.packageName
                            ) ?: false
                            
                            if (isActive && !activeMicApps.contains(app.packageName)) {
                                activeMicApps.add(app.packageName)
                                val appName = getAppName(app.packageName)
                                sendEvent(mapOf(
                                    "type" to "permission_active_changed",
                                    "operation" to AppOpsManager.OPSTR_RECORD_AUDIO,
                                    "operationName" to "麦克风",
                                    "packageName" to app.packageName,
                                    "appName" to appName,
                                    "uid" to app.uid,
                                    "active" to true,
                                    "timestamp" to System.currentTimeMillis()
                                ))
                            }
                        } catch (e: Exception) {
                            // Skip
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startAppOpsMonitoring() {
        // Only monitor camera and microphone with startWatchingActive
        // These are the only ops that support this API reliably
        val ops = listOf(
            AppOpsManager.OPSTR_CAMERA,
            AppOpsManager.OPSTR_RECORD_AUDIO,
            AppOpsManager.OPSTR_FINE_LOCATION,
            AppOpsManager.OPSTR_COARSE_LOCATION
        )
        
        for (op in ops) {
            try {
                val callback = AppOpsManager.OnOpActiveChangedListener { opStr, uid, packageName, active ->
                    val appName = getAppName(packageName)
                    sendEvent(mapOf(
                        "type" to "permission_active_changed",
                        "operation" to opStr,
                        "operationName" to getOperationName(opStr),
                        "packageName" to packageName,
                        "appName" to appName,
                        "uid" to uid,
                        "active" to active,
                        "timestamp" to System.currentTimeMillis()
                    ))
                    
                    // Update tracking sets
                    when (opStr) {
                        AppOpsManager.OPSTR_CAMERA -> {
                            if (active) activeCameraApps.add(packageName)
                            else activeCameraApps.remove(packageName)
                        }
                        AppOpsManager.OPSTR_RECORD_AUDIO -> {
                            if (active) activeMicApps.add(packageName)
                            else activeMicApps.remove(packageName)
                        }
                    }
                }
                appOpsManager?.startWatchingActive(arrayOf(op), executor, callback)
                opCallbacks.add(callback)
            } catch (e: Exception) {
                sendEvent(mapOf(
                    "type" to "error",
                    "message" to "无法监听 ${getOperationName(op)}: ${e.message}",
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }
    
    private fun startTelephonyMonitoring() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startTelephonyMonitoringApi31()
            } else {
                startTelephonyMonitoringLegacy()
            }
        } catch (e: SecurityException) {
            sendEvent(mapOf(
                "type" to "error",
                "message" to "需要READ_PHONE_STATE权限来监听通话状态: ${e.message}",
                "timestamp" to System.currentTimeMillis()
            ))
        } catch (e: Exception) {
            sendEvent(mapOf(
                "type" to "error",
                "message" to "通话监听启动失败: ${e.message}",
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.S)
    private fun startTelephonyMonitoringApi31() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val stateName = when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> "idle"
                    TelephonyManager.CALL_STATE_RINGING -> "ringing"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                    else -> "unknown"
                }
                val stateDesc = when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> "空闲"
                    TelephonyManager.CALL_STATE_RINGING -> "来电响铃中"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "通话中"
                    else -> "未知"
                }
                sendEvent(mapOf(
                    "type" to "call_state_changed",
                    "state" to stateName,
                    "stateDesc" to stateDesc,
                    "stateCode" to state,
                    "operationName" to "通话",
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
        telephonyManager?.registerTelephonyCallback(executor, callback)
        opCallbacks.add(callback)
    }
    
    @Suppress("DEPRECATION")
    private fun startTelephonyMonitoringLegacy() {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val stateName = when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> "idle"
                    TelephonyManager.CALL_STATE_RINGING -> "ringing"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                    else -> "unknown"
                }
                val stateDesc = when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> "空闲"
                    TelephonyManager.CALL_STATE_RINGING -> "来电响铃中"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "通话中"
                    else -> "未知"
                }
                sendEvent(mapOf(
                    "type" to "call_state_changed",
                    "state" to stateName,
                    "stateDesc" to stateDesc,
                    "stateCode" to state,
                    "operationName" to "通话",
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        opCallbacks.add(listener)
    }
    
    private fun startSmsMonitoring() {
        // Monitor SMS content changes
        smsObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                sendEvent(mapOf(
                    "type" to "sms_activity",
                    "uri" to (uri?.toString() ?: "unknown"),
                    "operationName" to "短信",
                    "message" to "检测到短信活动",
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
        
        try {
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                smsObserver!!
            )
            opCallbacks.add(smsObserver!!)
        } catch (e: Exception) {
            sendEvent(mapOf(
                "type" to "error",
                "message" to "短信监听启动失败: ${e.message}",
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }
    
    private fun startUsageStatsPolling() {
        if (!hasUsageStatsPermission()) return
        
        pollingRunnable = object : Runnable {
            private var lastCheckTime = System.currentTimeMillis()
            
            override fun run() {
                if (!isMonitoring) return
                
                checkRecentUsage()
                lastCheckTime = System.currentTimeMillis()
                mainHandler.postDelayed(this, 3000) // Poll every 3 seconds
            }
            
            private fun checkRecentUsage() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    executor.execute {
                        try {
                            val endTime = System.currentTimeMillis()
                            val startTime = endTime - 5000 // Last 5 seconds
                            
                            val events = usageStatsManager?.queryEvents(startTime, endTime)
                            val event = UsageEvents.Event()
                            
                            while (events?.hasNextEvent() == true) {
                                events.getNextEvent(event)
                                
                                // Check for foreground activity changes
                                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                                    // App came to foreground
                                    checkAppPermissionUsageNow(event.packageName)
                                }
                            }
                        } catch (e: Exception) {
                            // Silently fail
                        }
                    }
                }
            }
        }
        mainHandler.post(pollingRunnable!!)
        opCallbacks.add(pollingRunnable!!)
    }
    
    private fun checkAppPermissionUsageNow(packageName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                val ops = listOf(
                    AppOpsManager.OPSTR_CAMERA to "相机",
                    AppOpsManager.OPSTR_RECORD_AUDIO to "麦克风",
                    AppOpsManager.OPSTR_FINE_LOCATION to "精确定位",
                    AppOpsManager.OPSTR_COARSE_LOCATION to "粗略定位"
                )
                
                for ((op, name) in ops) {
                    val isActive = appOpsManager?.isOpActive(op, appInfo.uid, packageName) ?: false
                    if (isActive) {
                        val appName = getAppName(packageName)
                        sendEvent(mapOf(
                            "type" to "permission_active_changed",
                            "operation" to op,
                            "operationName" to name,
                            "packageName" to packageName,
                            "appName" to appName,
                            "uid" to appInfo.uid,
                            "active" to true,
                            "timestamp" to System.currentTimeMillis()
                        ))
                    }
                }
            } catch (e: Exception) {
                // Skip
            }
        }
    }
    
    private fun stopMonitoring() {
        isMonitoring = false
        
        // Stop camera monitoring
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && cameraCallback != null) {
            cameraManager?.unregisterAvailabilityCallback(cameraCallback!!)
        }
        
        // Stop audio recording monitoring
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && audioRecordingCallback != null) {
            audioManager?.unregisterAudioRecordingCallback(audioRecordingCallback!!)
        }
        
        // Stop SMS monitoring
        if (smsObserver != null) {
            context.contentResolver.unregisterContentObserver(smsObserver!!)
        }
        
        // Stop AppOps monitoring
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (callback in opCallbacks) {
                try {
                    if (callback is AppOpsManager.OnOpActiveChangedListener) {
                        appOpsManager?.stopWatchingActive(callback)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        // Stop telephony monitoring
        for (callback in opCallbacks) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && callback is TelephonyCallback) {
                    telephonyManager?.unregisterTelephonyCallback(callback)
                } else if (callback is PhoneStateListener) {
                    @Suppress("DEPRECATION")
                    telephonyManager?.listen(callback, PhoneStateListener.LISTEN_NONE)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        mainHandler.removeCallbacksAndMessages(null)
        opCallbacks.clear()
        activeCameraApps.clear()
        activeMicApps.clear()
        
        sendEvent(mapOf(
            "type" to "monitoring_stopped",
            "timestamp" to System.currentTimeMillis()
        ))
    }
    
    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val mode = appOpsManager?.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }
    
    private fun openUsageStatsSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Try alternative
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                sendEvent(mapOf(
                    "type" to "error",
                    "message" to "无法打开设置页面",
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }
    
    private fun getRecentPermissionUsage(minutes: Int): List<Map<String, Any>> {
        val usageList = mutableListOf<Map<String, Any>>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasUsageStatsPermission()) {
            try {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - (minutes * 60 * 1000L)
                
                val packages = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                val ops = listOf(
                    AppOpsManager.OPSTR_CAMERA to "相机",
                    AppOpsManager.OPSTR_RECORD_AUDIO to "麦克风",
                    AppOpsManager.OPSTR_FINE_LOCATION to "精确定位",
                    AppOpsManager.OPSTR_COARSE_LOCATION to "粗略定位"
                )
                
                for (app in packages) {
                    for ((op, name) in ops) {
                        try {
                            // Check if currently active
                            val isActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                appOpsManager?.isOpActive(op, app.uid, app.packageName) ?: false
                            } else {
                                false
                            }
                            
                            if (isActive) {
                                usageList.add(mapOf(
                                    "packageName" to app.packageName,
                                    "appName" to getAppName(app.packageName),
                                    "operation" to op,
                                    "operationName" to name,
                                    "active" to true,
                                    "timestamp" to System.currentTimeMillis()
                                ))
                            }
                        } catch (e: Exception) {
                            // Skip
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return usageList
    }
    
    private fun getSystemInfo(): Map<String, Any> {
        return mapOf(
            "sdkVersion" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE,
            "isHarmonyOS" to isHarmonyOS(),
            "harmonyVersion" to getHarmonyVersion(),
            "hasUsageStatsPermission" to hasUsageStatsPermission()
        )
    }
    
    private fun checkPermissionStatus(permissionType: String?): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        when (permissionType) {
            "camera" -> {
                result["granted"] = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                result["permission"] = Manifest.permission.CAMERA
            }
            "microphone" -> {
                result["granted"] = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                result["permission"] = Manifest.permission.RECORD_AUDIO
            }
            "location" -> {
                val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                result["granted"] = fine || coarse
                result["fineLocation"] = fine
                result["coarseLocation"] = coarse
            }
            "phone" -> {
                result["granted"] = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                result["permission"] = Manifest.permission.READ_PHONE_STATE
            }
            "sms" -> {
                val read = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                val receive = context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                result["granted"] = read || receive
                result["readSms"] = read
                result["receiveSms"] = receive
            }
            "usageStats" -> {
                result["granted"] = hasUsageStatsPermission()
            }
            else -> {
                result["error"] = "Unknown permission type"
            }
        }
        
        return result
    }
    
    private fun getActivePermissionUsage(): List<Map<String, Any>> {
        val usageList = mutableListOf<Map<String, Any>>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasUsageStatsPermission()) {
            try {
                val packages = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                val ops = listOf(
                    AppOpsManager.OPSTR_CAMERA to "相机",
                    AppOpsManager.OPSTR_RECORD_AUDIO to "麦克风",
                    AppOpsManager.OPSTR_FINE_LOCATION to "精确定位",
                    AppOpsManager.OPSTR_COARSE_LOCATION to "粗略定位"
                )
                
                for (app in packages) {
                    for ((op, name) in ops) {
                        try {
                            val isActive = appOpsManager?.isOpActive(op, app.uid, app.packageName) ?: false
                            
                            if (isActive) {
                                usageList.add(mapOf(
                                    "packageName" to app.packageName,
                                    "appName" to getAppName(app.packageName),
                                    "operation" to op,
                                    "operationName" to name,
                                    "active" to true
                                ))
                            }
                        } catch (e: Exception) {
                            // Skip this app/op combination
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return usageList
    }
    
    private fun isHarmonyOS(): Boolean {
        return try {
            val clazz = Class.forName("com.huawei.system.BuildEx")
            val method = clazz.getMethod("getOsBrand")
            val osBrand = method.invoke(null) as? String
            osBrand?.equals("harmony", ignoreCase = true) == true || 
            osBrand?.equals("harmonyos", ignoreCase = true) == true
        } catch (e: Exception) {
            try {
                Class.forName("ohos.system.version.SystemVersion")
                true
            } catch (e2: Exception) {
                try {
                    val prop = System.getProperty("ro.build.harmonyos.version")
                    !prop.isNullOrEmpty()
                } catch (e3: Exception) {
                    false
                }
            }
        }
    }
    
    private fun getHarmonyVersion(): String {
        return try {
            val clazz = Class.forName("com.huawei.system.BuildEx")
            val method = clazz.getMethod("getOsVersion")
            method.invoke(null) as? String ?: "unknown"
        } catch (e: Exception) {
            try {
                System.getProperty("ro.build.harmonyos.version") ?: "unknown"
            } catch (e2: Exception) {
                "unknown"
            }
        }
    }
    
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
    
    private fun getOperationName(op: String): String {
        return when (op) {
            AppOpsManager.OPSTR_CAMERA -> "相机"
            AppOpsManager.OPSTR_RECORD_AUDIO -> "麦克风"
            AppOpsManager.OPSTR_FINE_LOCATION -> "精确定位"
            AppOpsManager.OPSTR_COARSE_LOCATION -> "粗略定位"
            AppOpsManager.OPSTR_READ_SMS -> "读取短信"
            AppOpsManager.OPSTR_RECEIVE_SMS -> "接收短信"
            AppOpsManager.OPSTR_READ_PHONE_STATE -> "读取手机状态"
            AppOpsManager.OPSTR_CALL_PHONE -> "拨打电话"
            else -> op
        }
    }
    
    private fun sendEvent(data: Map<String, Any>) {
        mainHandler.post {
            eventSink?.success(data)
        }
    }
}
