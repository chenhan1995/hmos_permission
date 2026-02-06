package com.example.hmos_permission

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.Manifest
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
    private var isMonitoring = false
    
    private val opCallbacks = mutableListOf<Any>()
    
    companion object {
        const val METHOD_CHANNEL = "com.example.hmos_permission/method"
        const val EVENT_CHANNEL = "com.example.hmos_permission/events"
        
        // AppOps constants
        const val OP_CAMERA = "android:camera"
        const val OP_RECORD_AUDIO = "android:record_audio"
        const val OP_FINE_LOCATION = "android:fine_location"
        const val OP_COARSE_LOCATION = "android:coarse_location"
        const val OP_READ_SMS = "android:read_sms"
        const val OP_RECEIVE_SMS = "android:receive_sms"
        const val OP_PHONE_CALL_CAMERA = "android:phone_call_camera"
        const val OP_PHONE_CALL_MICROPHONE = "android:phone_call_microphone"
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
            else -> result.notImplemented()
        }
    }
    
    private fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startAppOpsMonitoringApi30()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startAppOpsMonitoringApi29()
        } else {
            startLegacyMonitoring()
        }
        
        startTelephonyMonitoring()
        
        sendEvent(mapOf(
            "type" to "monitoring_started",
            "timestamp" to System.currentTimeMillis(),
            "apiLevel" to Build.VERSION.SDK_INT
        ))
    }
    
    @RequiresApi(Build.VERSION_CODES.R)
    private fun startAppOpsMonitoringApi30() {
        val ops = listOf(
            AppOpsManager.OPSTR_CAMERA,
            AppOpsManager.OPSTR_RECORD_AUDIO,
            AppOpsManager.OPSTR_FINE_LOCATION,
            AppOpsManager.OPSTR_COARSE_LOCATION,
            AppOpsManager.OPSTR_READ_SMS,
            AppOpsManager.OPSTR_RECEIVE_SMS,
            AppOpsManager.OPSTR_READ_PHONE_STATE,
            AppOpsManager.OPSTR_CALL_PHONE
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
                }
                appOpsManager?.startWatchingActive(arrayOf(op), executor, callback)
                opCallbacks.add(callback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Monitor op noted (when permission is actually used)
        try {
            val notedCallback = object : AppOpsManager.OnOpNotedCallback() {
                override fun onNoted(op: SyncNotedAppOp) {
                    sendEvent(mapOf(
                        "type" to "permission_noted",
                        "operation" to op.op,
                        "operationName" to getOperationName(op.op),
                        "attributionTag" to (op.attributionTag ?: "unknown"),
                        "timestamp" to System.currentTimeMillis()
                    ))
                }

                override fun onSelfNoted(op: SyncNotedAppOp) {
                    sendEvent(mapOf(
                        "type" to "permission_self_noted",
                        "operation" to op.op,
                        "operationName" to getOperationName(op.op),
                        "timestamp" to System.currentTimeMillis()
                    ))
                }

                override fun onAsyncNoted(asyncOp: AsyncNotedAppOp) {
                    sendEvent(mapOf(
                        "type" to "permission_async_noted",
                        "operation" to asyncOp.op,
                        "operationName" to getOperationName(asyncOp.op),
                        "message" to (asyncOp.message ?: ""),
                        "timestamp" to System.currentTimeMillis()
                    ))
                }
            }
            AppOpsManager.setOnOpNotedCallback(executor, notedCallback)
            opCallbacks.add(notedCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startAppOpsMonitoringApi29() {
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
                }
                appOpsManager?.startWatchingActive(arrayOf(op), executor, callback)
                opCallbacks.add(callback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun startLegacyMonitoring() {
        // For older Android versions, use polling mechanism
        val pollingRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                
                checkLegacyPermissionUsage()
                mainHandler.postDelayed(this, 2000) // Poll every 2 seconds
            }
        }
        mainHandler.post(pollingRunnable)
        opCallbacks.add(pollingRunnable)
    }
    
    private fun checkLegacyPermissionUsage() {
        // Check active usage through package manager and app ops
        try {
            val packages = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in packages) {
                checkAppPermissionUsage(app.packageName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun checkAppPermissionUsage(packageName: String) {
        try {
            val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
            val ops = listOf(
                AppOpsManager.OPSTR_CAMERA,
                AppOpsManager.OPSTR_RECORD_AUDIO,
                AppOpsManager.OPSTR_FINE_LOCATION,
                AppOpsManager.OPSTR_COARSE_LOCATION
            )
            
            for (op in ops) {
                val mode = appOpsManager?.unsafeCheckOpNoThrow(op, uid, packageName)
                if (mode == AppOpsManager.MODE_ALLOWED) {
                    // Permission is allowed, could be in use
                }
            }
        } catch (e: Exception) {
            // Package not found or other error
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
                "message" to "需要READ_PHONE_STATE权限来监听通话状态",
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
                sendEvent(mapOf(
                    "type" to "call_state_changed",
                    "state" to stateName,
                    "stateCode" to state,
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
                sendEvent(mapOf(
                    "type" to "call_state_changed",
                    "state" to stateName,
                    "stateCode" to state,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        opCallbacks.add(listener)
    }
    
    private fun stopMonitoring() {
        isMonitoring = false
        
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                AppOpsManager.setOnOpNotedCallback(null, null)
            } catch (e: Exception) {
                e.printStackTrace()
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
        
        sendEvent(mapOf(
            "type" to "monitoring_stopped",
            "timestamp" to System.currentTimeMillis()
        ))
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
            "harmonyVersion" to getHarmonyVersion()
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
            else -> {
                result["error"] = "Unknown permission type"
            }
        }
        
        return result
    }
    
    private fun getActivePermissionUsage(): List<Map<String, Any>> {
        val usageList = mutableListOf<Map<String, Any>>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val packages = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                val ops = listOf(
                    AppOpsManager.OPSTR_CAMERA,
                    AppOpsManager.OPSTR_RECORD_AUDIO,
                    AppOpsManager.OPSTR_FINE_LOCATION,
                    AppOpsManager.OPSTR_COARSE_LOCATION
                )
                
                for (app in packages) {
                    for (op in ops) {
                        try {
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
                                    "operationName" to getOperationName(op),
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
            // Try alternative detection
            try {
                val clazz = Class.forName("ohos.system.version.SystemVersion")
                true
            } catch (e2: Exception) {
                // Check system properties
                try {
                    val prop = System.getProperty("ro.build.harmonyos.version")
                    !prop.isNullOrEmpty()
                } catch (e3: Exception) {
                    Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) && 
                    Build.VERSION.SDK_INT >= 30
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
