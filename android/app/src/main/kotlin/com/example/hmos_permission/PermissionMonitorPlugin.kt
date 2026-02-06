package com.example.hmos_permission

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.Manifest
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class PermissionMonitorPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var context: Context
    private var eventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var eventListener: PermissionMonitorManager.EventListener? = null
    
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
                setupEventListener()
                
                // 发送历史事件给新连接的UI
                sendHistoryEvents()
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
                removeEventListener()
            }
        })
        
        // 初始化管理器
        PermissionMonitorManager.initialize(context)
        
        // 如果服务正在运行，恢复监听状态
        if (PermissionMonitorService.isServiceRunning()) {
            setupEventListener()
        }
    }
    
    private fun setupEventListener() {
        if (eventListener != null) return
        
        eventListener = object : PermissionMonitorManager.EventListener {
            override fun onEvent(event: Map<String, Any?>) {
                mainHandler.post {
                    eventSink?.success(event)
                }
            }
        }
        PermissionMonitorManager.addListener(eventListener!!)
    }
    
    private fun removeEventListener() {
        eventListener?.let {
            PermissionMonitorManager.removeListener(it)
            eventListener = null
        }
    }
    
    private fun sendHistoryEvents() {
        val history = PermissionMonitorManager.getEventHistory()
        // 发送最近的事件（倒序，最新的在前）
        history.take(50).reversed().forEach { event ->
            mainHandler.post {
                eventSink?.success(event)
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        removeEventListener()
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
            "startForegroundService" -> {
                startForegroundService()
                result.success(true)
            }
            "stopForegroundService" -> {
                stopForegroundService()
                result.success(true)
            }
            "isServiceRunning" -> {
                result.success(PermissionMonitorService.isServiceRunning())
            }
            "setAutoStartOnBoot" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                PermissionMonitorService.setAutoStartOnBoot(context, enabled)
                result.success(true)
            }
            "isAutoStartOnBoot" -> {
                result.success(PermissionMonitorService.isAutoStartOnBoot(context))
            }
            "getSystemInfo" -> {
                result.success(PermissionMonitorManager.getSystemInfo())
            }
            "checkPermissionStatus" -> {
                val permissionType = call.argument<String>("type")
                result.success(checkPermissionStatus(permissionType))
            }
            "getActivePermissionUsage" -> {
                result.success(getActivePermissionUsage())
            }
            "isHarmonyOS" -> {
                result.success(HarmonyOSAdapter.isHarmonyOS())
            }
            "hasUsageStatsPermission" -> {
                result.success(PermissionMonitorManager.hasUsageStatsPermission())
            }
            "openUsageStatsSettings" -> {
                PermissionMonitorManager.openUsageStatsSettings()
                result.success(true)
            }
            "getEventHistory" -> {
                result.success(PermissionMonitorManager.getEventHistory())
            }
            else -> result.notImplemented()
        }
    }
    
    private fun startMonitoring() {
        PermissionMonitorManager.initialize(context)
        PermissionMonitorManager.startMonitoring()
        setupEventListener()
    }
    
    private fun stopMonitoring() {
        PermissionMonitorManager.stopMonitoring()
    }
    
    private fun startForegroundService() {
        PermissionMonitorService.start(context)
        setupEventListener()
    }
    
    private fun stopForegroundService() {
        PermissionMonitorService.stop(context)
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
