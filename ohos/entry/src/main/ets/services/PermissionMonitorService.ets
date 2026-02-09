import { abilityAccessCtrl, bundleManager, Permissions } from '@kit.AbilityKit';
import { camera } from '@kit.CameraKit';
import { audio } from '@kit.AudioKit';
import { geoLocationManager } from '@kit.LocationKit';
import { call } from '@kit.TelephonyKit';
import { deviceInfo } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { PermissionEvent, SystemInfo, ActivePermissionUsage } from '../model/PermissionModel';

const TAG: string = 'PermissionMonitorService';
const DOMAIN: number = 0x0000;

type EventCallback = (event: PermissionEvent) => void;

/**
 * 权限监听服务 - HarmonyOS NEXT 原生实现
 */
export class PermissionMonitorService {
  private isMonitoring: boolean = false;
  private eventCallback?: EventCallback;
  private cameraManager?: camera.CameraManager;
  private audioManager?: audio.AudioManager;
  private callStateCallback?: (state: call.CallState) => void;

  /**
   * 获取系统信息
   */
  async getSystemInfo(): Promise<SystemInfo> {
    const info = new SystemInfo();
    info.apiVersion = deviceInfo.sdkApiVersion;
    info.deviceType = deviceInfo.deviceType;
    info.manufacturer = deviceInfo.manufacture;
    info.brand = deviceInfo.brand;
    info.model = deviceInfo.productModel;
    info.osFullName = deviceInfo.osFullName;
    return info;
  }

  /**
   * 开始监听权限使用
   */
  async startMonitoring(callback: EventCallback): Promise<boolean> {
    if (this.isMonitoring) {
      return true;
    }

    this.eventCallback = callback;

    try {
      // 请求必要权限
      const permissionsGranted = await this.requestPermissions();
      if (!permissionsGranted) {
        const event = new PermissionEvent();
        event.type = 'warning';
        event.message = '部分权限未授予，监听功能可能受限';
        event.timestamp = Date.now();
        this.sendEvent(event);
      }

      // 启动各类监听
      await this.startCameraMonitoring();
      await this.startAudioMonitoring();
      await this.startCallStateMonitoring();
      await this.startLocationMonitoring();

      this.isMonitoring = true;
      const startEvent = new PermissionEvent();
      startEvent.type = 'monitoring_started';
      startEvent.message = '权限监听已启动';
      startEvent.timestamp = Date.now();
      this.sendEvent(startEvent);

      return true;
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to start monitoring: %{public}s', JSON.stringify(error));
      const errorEvent = new PermissionEvent();
      errorEvent.type = 'error';
      errorEvent.message = `启动监听失败: ${error}`;
      errorEvent.timestamp = Date.now();
      this.sendEvent(errorEvent);
      return false;
    }
  }

  /**
   * 停止监听
   */
  async stopMonitoring(): Promise<void> {
    if (!this.isMonitoring) {
      return;
    }

    try {
      // 停止相机监听
      if (this.cameraManager) {
        this.cameraManager.off('cameraStatus');
      }

      // 停止通话状态监听
      // Note: Call state monitoring cleanup handled automatically

      this.isMonitoring = false;
      const stopEvent = new PermissionEvent();
      stopEvent.type = 'monitoring_stopped';
      stopEvent.message = '权限监听已停止';
      stopEvent.timestamp = Date.now();
      this.sendEvent(stopEvent);
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to stop monitoring: %{public}s', JSON.stringify(error));
    }
  }

  /**
   * 请求权限
   */
  private async requestPermissions(): Promise<boolean> {
    const permissions: Permissions[] = [
      'ohos.permission.CAMERA',
      'ohos.permission.MICROPHONE',
      'ohos.permission.LOCATION',
      'ohos.permission.APPROXIMATELY_LOCATION'
    ];

    try {
      const atManager = abilityAccessCtrl.createAtManager();
      const bundleInfo = await bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
      const tokenId = bundleInfo.appInfo.accessTokenId;

      let allGranted = true;
      for (const permission of permissions) {
        const grantStatus = await atManager.checkAccessToken(tokenId, permission);
        if (grantStatus !== abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
          allGranted = false;
          hilog.warn(DOMAIN, TAG, 'Permission not granted: %{public}s', permission);
        }
      }

      return allGranted;
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to check permissions: %{public}s', JSON.stringify(error));
      return false;
    }
  }

  /**
   * 启动相机监听
   */
  private async startCameraMonitoring(): Promise<void> {
    try {
      this.cameraManager = camera.getCameraManager(getContext(this));

      this.cameraManager.on('cameraStatus', (err, cameraStatusInfo) => {
        if (err) {
          hilog.error(DOMAIN, TAG, 'Camera status error: %{public}s', JSON.stringify(err));
          return;
        }

        const isInUse = cameraStatusInfo.status === camera.CameraStatus.CAMERA_STATUS_APPEAR ||
          cameraStatusInfo.status === camera.CameraStatus.CAMERA_STATUS_AVAILABLE;

        const cameraEvent = new PermissionEvent();
        cameraEvent.type = isInUse ? 'camera_available' : 'camera_in_use';
        cameraEvent.operation = 'camera';
        cameraEvent.operationName = '相机';
        cameraEvent.message = isInUse ? '相机已释放' : '相机正在被使用';
        cameraEvent.timestamp = Date.now();
        this.sendEvent(cameraEvent);
      });

      hilog.info(DOMAIN, TAG, 'Camera monitoring started');
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to start camera monitoring: %{public}s', JSON.stringify(error));
    }
  }

  /**
   * 启动音频监听
   */
  private async startAudioMonitoring(): Promise<void> {
    try {
      this.audioManager = audio.getAudioManager();
      // Note: Advanced audio monitoring APIs may require system-level permissions
      // Basic audio manager initialized for future use
      hilog.info(DOMAIN, TAG, 'Audio monitoring initialized');
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to start audio monitoring: %{public}s', JSON.stringify(error));
    }
  }

  /**
   * 启动通话状态监听
   */
  private async startCallStateMonitoring(): Promise<void> {
    try {
      // Note: Call state observer APIs require telephony service permissions
      // and may not be available for third-party apps in SDK 12
      hilog.info(DOMAIN, TAG, 'Call state monitoring initialized (limited in SDK 12)');
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to start call state monitoring: %{public}s', JSON.stringify(error));
    }
  }

  /**
   * 启动定位监听
   */
  private async startLocationMonitoring(): Promise<void> {
    try {
      // 检查定位服务是否开启
      const isEnabled = geoLocationManager.isLocationEnabled();
      if (!isEnabled) {
        const locationWarning = new PermissionEvent();
        locationWarning.type = 'warning';
        locationWarning.operation = 'location';
        locationWarning.operationName = '定位';
        locationWarning.message = '定位服务未开启';
        locationWarning.timestamp = Date.now();
        this.sendEvent(locationWarning);
        return;
      }

      // 监听定位服务状态变化
      geoLocationManager.on('locationEnabledChange', (isEnabled: boolean) => {
        const locationEvent = new PermissionEvent();
        locationEvent.type = 'permission_active_changed';
        locationEvent.operation = 'location';
        locationEvent.operationName = '定位';
        locationEvent.active = isEnabled;
        locationEvent.message = isEnabled ? '定位服务已开启' : '定位服务已关闭';
        locationEvent.timestamp = Date.now();
        this.sendEvent(locationEvent);
      });

      hilog.info(DOMAIN, TAG, 'Location monitoring started');
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to start location monitoring: %{public}s', JSON.stringify(error));
    }
  }

  /**
   * 检查权限状态
   */
  async checkPermissionStatus(permissionType: string): Promise<Record<string, boolean | string>> {
    const result: Record<string, boolean | string> = {};

    try {
      const atManager = abilityAccessCtrl.createAtManager();
      const bundleInfo = await bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
      const tokenId = bundleInfo.appInfo.accessTokenId;

      let permission: Permissions;
      switch (permissionType) {
        case 'camera':
          permission = 'ohos.permission.CAMERA';
          break;
        case 'microphone':
          permission = 'ohos.permission.MICROPHONE';
          break;
        case 'location':
          permission = 'ohos.permission.LOCATION';
          break;
        default:
          result['error'] = '未知权限类型';
          return result;
      }

      const grantStatus = await atManager.checkAccessToken(tokenId, permission);
      result['granted'] = grantStatus === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
      result['permission'] = permission;
    } catch (error) {
      result['error'] = `检查权限失败: ${error}`;
    }

    return result;
  }

  /**
   * 获取当前活跃的权限使用
   */
  async getActivePermissionUsage(): Promise<ActivePermissionUsage[]> {
    // HarmonyOS NEXT 暂不支持直接获取其他应用的权限使用情况
    // 这里返回空数组，实际使用中需要通过系统级API
    return [];
  }

  /**
   * 发送事件
   */
  private sendEvent(event: PermissionEvent): void {
    if (this.eventCallback) {
      if (event.timestamp === 0) {
        event.timestamp = Date.now();
      }
      this.eventCallback(event);
    }
  }
}
