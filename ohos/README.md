# HarmonyOS NEXT 权限监听器

本目录包含纯血鸿蒙（HarmonyOS NEXT）的原生实现。

## 项目结构

```
ohos/
├── AppScope/                    # 应用级配置
│   ├── app.json5               # 应用配置
│   └── resources/              # 应用级资源
├── entry/                       # 主模块
│   ├── src/main/
│   │   ├── ets/
│   │   │   ├── entryability/   # 入口 Ability
│   │   │   ├── pages/          # 页面
│   │   │   ├── services/       # 服务
│   │   │   ├── model/          # 数据模型
│   │   │   ├── plugin/         # Flutter 插件桥接
│   │   │   └── extension/      # 后台服务扩展
│   │   ├── resources/          # 模块资源
│   │   └── module.json5        # 模块配置
│   ├── build-profile.json5
│   └── oh-package.json5
├── build-profile.json5          # 构建配置
├── oh-package.json5             # 包配置
└── hvigorfile.ts               # 构建脚本
```

## 环境要求

- **DevEco Studio**: 5.0.0 或更高版本
- **HarmonyOS SDK**: API 12 或更高
- **Node.js**: 16.x 或更高

## 开发环境配置

### 1. 安装 DevEco Studio

1. 访问 [华为开发者联盟](https://developer.huawei.com/consumer/cn/deveco-studio/)
2. 下载并安装 DevEco Studio 5.0.0+
3. 首次启动时配置 HarmonyOS SDK

### 2. 打开项目

1. 启动 DevEco Studio
2. 选择 `File > Open`
3. 选择 `ohos` 目录
4. 等待项目同步完成

### 3. 配置签名

1. 打开 `File > Project Structure > Signing Configs`
2. 勾选 `Automatically generate signature`
3. 登录华为开发者账号
4. 选择证书和 Profile

## 打包说明

### 打包 HAP (HarmonyOS Ability Package)

HAP 是 HarmonyOS 应用的基本部署单元。

#### 方式一：DevEco Studio 图形界面

1. 打开 DevEco Studio
2. 选择 `Build > Build Hap(s)/APP(s) > Build Hap(s)`
3. 选择构建类型（Debug/Release）
4. 等待构建完成
5. 输出路径：`entry/build/default/outputs/default/entry-default-signed.hap`

#### 方式二：命令行构建

```bash
# 进入 ohos 目录
cd ohos

# 安装依赖
ohpm install

# Debug 构建
hvigorw assembleHap --mode module -p product=default -p buildMode=debug

# Release 构建
hvigorw assembleHap --mode module -p product=default -p buildMode=release
```

### 打包 HAK (HarmonyOS Archive Kit)

HAK 是可复用的共享库包，适用于将功能模块化后供其他项目使用。

#### 创建 HAK 模块

1. 在 DevEco Studio 中右键项目根目录
2. 选择 `New > Module`
3. 选择 `Static Library` 或 `Shared Library`
4. 配置模块名称（如 `permission_monitor_hak`）

#### 配置 HAK 模块

修改新模块的 `build-profile.json5`：

```json5
{
  "apiType": "stageMode",
  "buildOption": {},
  "targets": [
    {
      "name": "default",
      "runtimeOS": "HarmonyOS"
    }
  ]
}
```

修改 `oh-package.json5`：

```json5
{
  "name": "@example/permission-monitor",
  "version": "1.0.0",
  "description": "Permission Monitor HAK",
  "main": "Index.ets",
  "types": "",
  "license": "MIT"
}
```

#### 构建 HAK

```bash
# 构建 HAK
hvigorw assembleHar --mode module -p module=permission_monitor_hak

# 输出路径
# permission_monitor_hak/build/default/outputs/default/permission_monitor_hak.har
```

### 打包 APP (应用包)

APP 包用于应用市场发布。

```bash
# 构建 Release APP
hvigorw assembleApp --mode project -p product=default -p buildMode=release

# 输出路径
# build/outputs/default/hmos_permission-default-signed.app
```

## 安装到设备

### 使用 DevEco Studio

1. 连接设备或启动模拟器
2. 点击 `Run` 按钮或按 `Shift + F10`

### 使用命令行

```bash
# 安装 HAP
hdc install entry/build/default/outputs/default/entry-default-signed.hap

# 卸载应用
hdc uninstall com.example.hmos_permission

# 查看日志
hdc hilog | grep PermissionMonitor
```

## 权限说明

应用需要以下权限：

| 权限 | 用途 | 授权方式 |
|------|------|----------|
| ohos.permission.CAMERA | 监听相机使用 | 用户授权 |
| ohos.permission.MICROPHONE | 监听麦克风使用 | 用户授权 |
| ohos.permission.LOCATION | 监听精确定位 | 用户授权 |
| ohos.permission.APPROXIMATELY_LOCATION | 监听粗略定位 | 用户授权 |
| ohos.permission.READ_CALL_LOG | 监听通话状态 | 用户授权 |
| ohos.permission.READ_MESSAGES | 监听短信权限 | 用户授权 |
| ohos.permission.KEEP_BACKGROUND_RUNNING | 后台运行 | 系统授权 |

## Flutter 集成

如需将此模块集成到 Flutter for HarmonyOS 项目：

### 1. 添加 Flutter OHOS 支持

```bash
# 确保 Flutter 支持 OHOS
flutter config --enable-ohos
```

### 2. 配置 pubspec.yaml

```yaml
flutter:
  platforms:
    ohos:
      package: com.example.hmos_permission
      module: entry
```

### 3. 使用插件桥接

`FlutterOhosPlugin.ets` 提供了与 Flutter 通信的接口，支持：

- `startMonitoring` - 开始监听
- `stopMonitoring` - 停止监听
- `getSystemInfo` - 获取系统信息
- `checkPermissionStatus` - 检查权限状态
- `isHarmonyOS` - 检测是否为鸿蒙系统

## 常见问题

### Q: 构建时提示找不到 SDK

确保在 DevEco Studio 中正确配置了 HarmonyOS SDK 路径：
`File > Settings > SDK`

### Q: 签名失败

1. 检查是否已登录华为开发者账号
2. 确保证书和 Profile 有效
3. 检查 `build-profile.json5` 中的签名配置

### Q: 权限请求被拒绝

HarmonyOS NEXT 采用更严格的权限管理，确保：
1. 在 `module.json5` 中正确声明权限
2. 提供合理的权限使用说明
3. 在运行时动态请求权限

## 参考文档

- [HarmonyOS 开发文档](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/application-dev-guide-V5)
- [ArkTS 语言指南](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/arkts-get-started-V5)
- [DevEco Studio 使用指南](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-tools-overview-V5)
