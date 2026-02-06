import { PermissionMonitorService } from '../services/PermissionMonitorService';
import { PermissionEvent, SystemInfo } from '../model/PermissionModel';
import { hilog } from '@kit.PerformanceAnalysisKit';

const TAG: string = 'FlutterOhosPlugin';
const DOMAIN: number = 0x0000;

/**
 * Flutter OHOS 插件桥接
 * 用于 Flutter for HarmonyOS 的 MethodChannel 通信
 * 
 * 注意：此文件需要配合 flutter_ohos SDK 使用
 * 当前为独立 ArkTS 实现，如需集成 Flutter，请参考 flutter_ohos 官方文档
 */
export class FlutterOhosPlugin {
  private static instance: FlutterOhosPlugin | null = null;
  private monitorService: PermissionMonitorService = new PermissionMonitorService();
  private eventCallback?: (event: Record<string, Object>) => void;

  static getInstance(): FlutterOhosPlugin {
    if (!FlutterOhosPlugin.instance) {
      FlutterOhosPlugin.instance = new FlutterOhosPlugin();
    }
    return FlutterOhosPlugin.instance;
  }

  /**
   * 设置事件回调（用于向 Flutter 发送事件）
   */
  setEventCallback(callback: (event: Record<string, Object>) => void): void {
    this.eventCallback = callback;
  }

  /**
   * 处理来自 Flutter 的方法调用
   */
  async onMethodCall(method: string, args?: Record<string, Object>): Promise<Object | null> {
    hilog.info(DOMAIN, TAG, 'Method call: %{public}s', method);

    switch (method) {
      case 'startMonitoring':
        return await this.startMonitoring();

      case 'stopMonitoring':
        return await this.stopMonitoring();

      case 'getSystemInfo':
        return await this.getSystemInfo();

      case 'checkPermissionStatus':
        const permType = args?.['type'] as string;
        return await this.checkPermissionStatus(permType);

      case 'getActivePermissionUsage':
        return await this.getActivePermissionUsage();

      case 'isHarmonyOS':
        return true; // 纯血鸿蒙始终返回 true

      case 'hasUsageStatsPermission':
        return await this.hasUsageStatsPermission();

      case 'openUsageStatsSettings':
        return await this.openUsageStatsSettings();

      case 'getRecentPermissionUsage':
        const minutes = args?.['minutes'] as number ?? 5;
        return await this.getRecentPermissionUsage(minutes);

      default:
        hilog.warn(DOMAIN, TAG, 'Unknown method: %{public}s', method);
        return null;
    }
  }

  private async startMonitoring(): Promise<boolean> {
    try {
      const result = await this.monitorService.startMonitoring((event: PermissionEvent) => {
        if (this.eventCallback) {
          this.eventCallback(this.eventToMap(event));
        }
      });
      return result;
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Start monitoring failed: %{public}s', JSON.stringify(error));
      return false;
    }
  }

  private async stopMonitoring(): Promise<boolean> {
    try {
      await this.monitorService.stopMonitoring();
      return true;
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Stop monitoring failed: %{public}s', JSON.stringify(error));
      return false;
    }
  }

  private async getSystemInfo(): Promise<Record<string, Object>> {
    const info = await this.monitorService.getSystemInfo();
    return {
      'sdkVersion': info.apiVersion,
      'release': info.osFullName,
      'manufacturer': info.manufacturer,
      'model': info.model,
      'brand': info.brand,
      'device': info.deviceType,
      'isHarmonyOS': true,
      'harmonyVersion': info.osFullName
    };
  }

  private async checkPermissionStatus(permissionType: string): Promise<Record<string, Object>> {
    return await this.monitorService.checkPermissionStatus(permissionType);
  }

  private async getActivePermissionUsage(): Promise<Object[]> {
    const usages = await this.monitorService.getActivePermissionUsage();
    return usages.map((usage) => ({
      'packageName': usage.packageName,
      'appName': usage.appName,
      'operation': usage.operation,
      'operationName': usage.operationName,
      'active': usage.active
    }));
  }

  private async hasUsageStatsPermission(): Promise<boolean> {
    // HarmonyOS NEXT 使用不同的权限模型
    return true;
  }

  private async openUsageStatsSettings(): Promise<boolean> {
    // TODO: 打开系统设置页面
    return true;
  }

  private async getRecentPermissionUsage(minutes: number): Promise<Object[]> {
    // 返回空数组，HarmonyOS NEXT 暂不支持此功能
    return [];
  }

  private eventToMap(event: PermissionEvent): Record<string, Object> {
    const map: Record<string, Object> = {
      'type': event.type,
      'timestamp': event.timestamp
    };

    if (event.operation) map['operation'] = event.operation;
    if (event.operationName) map['operationName'] = event.operationName;
    if (event.packageName) map['packageName'] = event.packageName;
    if (event.appName) map['appName'] = event.appName;
    if (event.uid !== undefined) map['uid'] = event.uid;
    if (event.active !== undefined) map['active'] = event.active;
    if (event.state) map['state'] = event.state;
    if (event.stateCode !== undefined) map['stateCode'] = event.stateCode;
    if (event.message) map['message'] = event.message;

    return map;
  }
}
