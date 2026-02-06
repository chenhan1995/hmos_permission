package com.example.hmos_permission

import android.content.Context
import android.os.Build
import java.lang.reflect.Method

/**
 * HarmonyOS 适配器
 * 用于检测和适配鸿蒙系统特有的API
 */
object HarmonyOSAdapter {
    
    private var isHarmonyOSCached: Boolean? = null
    private var harmonyVersionCached: String? = null
    
    /**
     * 检测当前系统是否为鸿蒙系统
     */
    fun isHarmonyOS(): Boolean {
        if (isHarmonyOSCached != null) {
            return isHarmonyOSCached!!
        }
        
        isHarmonyOSCached = checkHarmonyOS()
        return isHarmonyOSCached!!
    }
    
    private fun checkHarmonyOS(): Boolean {
        // 方法1: 通过 BuildEx 类检测
        try {
            val clazz = Class.forName("com.huawei.system.BuildEx")
            val method = clazz.getMethod("getOsBrand")
            val osBrand = method.invoke(null) as? String
            if (osBrand?.equals("harmony", ignoreCase = true) == true ||
                osBrand?.equals("harmonyos", ignoreCase = true) == true) {
                return true
            }
        } catch (e: Exception) {
            // 继续尝试其他方法
        }
        
        // 方法2: 通过 ohos 包检测
        try {
            Class.forName("ohos.system.version.SystemVersion")
            return true
        } catch (e: Exception) {
            // 继续尝试其他方法
        }
        
        // 方法3: 通过系统属性检测
        try {
            val prop = getSystemProperty("ro.build.harmonyos.version")
            if (!prop.isNullOrEmpty()) {
                return true
            }
        } catch (e: Exception) {
            // 继续尝试其他方法
        }
        
        // 方法4: 通过 HarmonyOS API 版本检测
        try {
            val apiVersion = getSystemProperty("hw_sc.build.platform.version")
            if (!apiVersion.isNullOrEmpty()) {
                return true
            }
        } catch (e: Exception) {
            // 忽略
        }
        
        // 方法5: 华为设备 + Android 10+ 可能是鸿蒙
        // 这是一个启发式检测，不完全准确
        if (Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
            Build.MANUFACTURER.equals("HONOR", ignoreCase = true)) {
            // 检查是否有鸿蒙特有的系统属性
            try {
                val emui = getSystemProperty("ro.build.version.emui")
                if (emui != null && emui.contains("HarmonyOS", ignoreCase = true)) {
                    return true
                }
            } catch (e: Exception) {
                // 忽略
            }
        }
        
        return false
    }
    
    /**
     * 获取鸿蒙系统版本
     */
    fun getHarmonyVersion(): String {
        if (harmonyVersionCached != null) {
            return harmonyVersionCached!!
        }
        
        harmonyVersionCached = detectHarmonyVersion()
        return harmonyVersionCached!!
    }
    
    private fun detectHarmonyVersion(): String {
        // 方法1: 通过 BuildEx 获取
        try {
            val clazz = Class.forName("com.huawei.system.BuildEx")
            val method = clazz.getMethod("getOsVersion")
            val version = method.invoke(null) as? String
            if (!version.isNullOrEmpty()) {
                return version
            }
        } catch (e: Exception) {
            // 继续尝试其他方法
        }
        
        // 方法2: 通过系统属性获取
        try {
            val version = getSystemProperty("ro.build.harmonyos.version")
            if (!version.isNullOrEmpty()) {
                return version
            }
        } catch (e: Exception) {
            // 继续尝试其他方法
        }
        
        // 方法3: 通过 hw_sc 属性获取
        try {
            val version = getSystemProperty("hw_sc.build.platform.version")
            if (!version.isNullOrEmpty()) {
                return "API $version"
            }
        } catch (e: Exception) {
            // 忽略
        }
        
        return "unknown"
    }
    
    /**
     * 获取系统属性
     */
    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 获取鸿蒙系统的 API 级别
     */
    fun getHarmonyAPILevel(): Int {
        try {
            val apiStr = getSystemProperty("hw_sc.build.platform.version")
            if (!apiStr.isNullOrEmpty()) {
                return apiStr.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            // 忽略
        }
        return 0
    }
    
    /**
     * 检查是否支持鸿蒙特有的权限管理API
     */
    fun supportsHarmonyPermissionAPI(): Boolean {
        if (!isHarmonyOS()) return false
        
        return try {
            // 尝试加载鸿蒙的权限管理类
            Class.forName("ohos.security.permission.PermissionKit")
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取设备信息（包含鸿蒙特有信息）
     */
    fun getDeviceInfo(): Map<String, Any> {
        val info = mutableMapOf<String, Any>()
        
        info["manufacturer"] = Build.MANUFACTURER
        info["brand"] = Build.BRAND
        info["model"] = Build.MODEL
        info["device"] = Build.DEVICE
        info["androidVersion"] = Build.VERSION.RELEASE
        info["androidSdk"] = Build.VERSION.SDK_INT
        info["isHarmonyOS"] = isHarmonyOS()
        
        if (isHarmonyOS()) {
            info["harmonyVersion"] = getHarmonyVersion()
            info["harmonyApiLevel"] = getHarmonyAPILevel()
            info["supportsHarmonyPermissionAPI"] = supportsHarmonyPermissionAPI()
        }
        
        // 尝试获取 EMUI 版本
        try {
            val emui = getSystemProperty("ro.build.version.emui")
            if (!emui.isNullOrEmpty()) {
                info["emuiVersion"] = emui
            }
        } catch (e: Exception) {
            // 忽略
        }
        
        return info
    }
    
    /**
     * 在鸿蒙系统上请求使用统计权限
     * 鸿蒙系统可能有不同的权限请求流程
     */
    fun requestUsageStatsPermission(context: Context): Boolean {
        return try {
            if (isHarmonyOS()) {
                // 鸿蒙系统可能使用不同的设置页面
                val intent = android.content.Intent()
                intent.action = "com.huawei.permissionmanager.ACTION_PERMISSION_MANAGER"
                intent.setPackage("com.huawei.systemmanager")
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            }
            
            // 回退到标准 Android 方式
            val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查应用是否有使用统计权限
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }
}
