package com.example.hmos_permission

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机启动广播接收器
 * 用于在设备启动后自动启动权限监听服务
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            // 检查是否启用了自动启动
            val prefs = context.getSharedPreferences("permission_monitor_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", false)
            
            if (autoStart) {
                PermissionMonitorService.start(context)
            }
        }
    }
}
