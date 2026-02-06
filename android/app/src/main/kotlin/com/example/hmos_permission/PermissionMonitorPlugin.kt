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
                result["granted"] = PermissionMonitorManager.hasUsageStatsPermission()
            }
            else -> {
                result["error"] = "Unknown permission type"
            }
        }
        
        return result
    }
    
    private fun getActivePermissionUsage(): List<Map<String, Any>> {
        val usageList = mutableListOf<Map<String, Any>>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && PermissionMonitorManager.hasUsageStatsPermission()) {
            try {
                val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
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
    
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
