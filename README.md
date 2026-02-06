# 权限监听器 (Permission Monitor)

一个用于监听 Android 和 HarmonyOS 系统权限使用情况的 Flutter 应用。

## 功能特性

- **相机监听**: 监听系统和其他应用使用相机的情况
- **麦克风监听**: 监听录音权限的使用
- **定位监听**: 监听精确定位和粗略定位的使用
- **通话监听**: 监听电话状态变化（空闲、响铃、通话中）
- **短信监听**: 监听短信读取和接收权限的使用
- **鸿蒙适配**: 自动检测并适配 HarmonyOS 系统

## 系统要求

- Android 6.0 (API 23) 及以上
- HarmonyOS 2.0 及以上（兼容模式）
- Flutter 3.10.0 及以上

## 技术实现

### Android 平台
- 使用 `AppOpsManager` 监听权限使用（API 29+）
- 使用 `AppOpsManager.OnOpActiveChangedListener` 实时监听权限激活状态（API 30+）
- 使用 `TelephonyCallback` / `PhoneStateListener` 监听通话状态
- 前台服务保持后台监听

### HarmonyOS 适配
- 自动检测 HarmonyOS 系统
- 通过反射调用 `com.huawei.system.BuildEx` 获取系统信息
- 兼容 HarmonyOS 的权限管理机制

## 安装使用

1. 克隆项目
```bash
git clone <repository-url>
cd hmos_permission
```

2. 安装依赖
```bash
flutter pub get
```

3. 运行应用
```bash
flutter run
```

4. 构建 APK
```bash
flutter build apk --release
```

## 权限说明

应用需要以下权限：

| 权限 | 用途 |
|------|------|
| CAMERA | 监听相机使用 |
| RECORD_AUDIO | 监听麦克风使用 |
| ACCESS_FINE_LOCATION | 监听精确定位 |
| ACCESS_COARSE_LOCATION | 监听粗略定位 |
| READ_PHONE_STATE | 监听通话状态 |
| READ_SMS / RECEIVE_SMS | 监听短信权限 |
| PACKAGE_USAGE_STATS | 监听其他应用权限使用（需手动授权） |

## 项目结构

```
lib/
├── main.dart                          # 应用入口
├── pages/
│   └── home_page.dart                 # 主页面
└── services/
    └── permission_monitor_service.dart # 权限监听服务

android/app/src/main/kotlin/.../
├── MainActivity.kt                    # 主Activity
├── PermissionMonitorPlugin.kt         # Flutter插件
├── PermissionMonitorService.kt        # 前台服务
├── HarmonyOSAdapter.kt               # 鸿蒙适配器
└── BootReceiver.kt                   # 开机启动接收器
```

## 注意事项

1. **监听其他应用权限使用**需要用户在系统设置中手动授予"使用情况访问权限"
2. 部分功能在 Android 10 (API 29) 以下版本可能受限
3. 鸿蒙系统的部分特性可能需要额外适配

## License

MIT License
