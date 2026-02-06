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
    return new SystemInfo({
      apiVersion: deviceInfo.sdkApiVersion,
      deviceType: deviceInfo.deviceType,
      manufacturer: deviceInfo.manufacture,
      brand: deviceInfo.brand,
      model: deviceInfo.productModel,
      osFullName: deviceInfo.osFullName
    });
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
        this.sendEvent({
          type: 'warning',
          message: '部分权限未授予，监听功能可能受限',
          timestamp: Date.now()
        });
      }

      // 启动各类监听
      await this.startCameraMonitoring();
      await this.startAudioMonitoring();
      await this.startCallStateMonitoring();
      await this.startLocationMonitoring();

      this.isMonitoring = true;
      this.sendEvent({
        type: 'monitoring_started',
        message: '权限监听已启动',
        timestamp: Date.now()
      });

      return true;
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to start monitoring: %{public}s', JSON.stringify(error));
      this.sendEvent({
        type: 'error',
        message: `启动监听失败: ${error}`,
        timestamp: Date.now()
      });
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
      if (this.callStateCallback) {
        call.off('callStateChange', this.callStateCallback);
      }

      this.isMonitoring = false;
      this.sendEvent({
        type: 'monitoring_stopped',
        message: '权限监听已停止',
        timestamp: Date.now()
      });
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

        this.sendEvent({
          type: isInUse ? 'camera_available' : 'camera_in_use',
          operation: 'camera',
          operationName: '相机',
          message: isInUse ? '相机已释放' : '相机正在被使用',
          timestamp: Date.now()
        });
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
      const audioRoutingManager = this.audioManager.getRoutingManager();

      // 监听音频设备变化
      audioRoutingManager.on('deviceChange', (deviceChangeAction) => {
        // 检测麦克风使用
        const hasMic = deviceChangeAction.deviceDescriptors.some(
          (device) => device.deviceType === audio.DeviceType.MIC
        );

        if (hasMic) {
          const isConnected = deviceChangeAction.type === audio.DeviceChangeType.CONNECT;
          this.sendEvent({
            type: isConnected ? 'audio_recording_started' : 'audio_recording_stopped',
            operation: 'microphone',
            operationName: '麦克风',
            message: isConnected ? '检测到麦克风活动' : '麦克风已停止',
            timestamp: Date.now()
          });
        }
      });

      hilog.info(DOMAIN, TAG, 'Audio monitoring started');
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to start audio monitoring: %{public}s', JSON.stringify(error));
    }
  }

  /**
   * 启动通话状态监听
   */
  private async startCallStateMonitoring(): Promise<void> {
    try {
      this.callStateCallback = (state: call.CallState) => {
        let stateName: string;
        let stateDesc: string;

        switch (state) {
          case call.CallState.CALL_STATE_IDLE:
            stateName = 'idle';
            stateDesc = '空闲';
            break;
          case call.CallState.CALL_STATE_RINGING:
            stateName = 'ringing';
            stateDesc = '来电响铃中';
            break;
          case call.CallState.CALL_STATE_OFFHOOK:
            stateName = 'offhook';
            stateDesc = '通话中';
            break;
          default:
            stateName = 'unknown';
            stateDesc = '未知';
        }

        this.sendEvent({
          type: 'call_state_changed',
          state: stateName,
          stateCode: state,
          operation: 'phone',
          operationName: '通话',
          message: `通话状态: ${stateDesc}`,
          timestamp: Date.now()
        });
      };

      call.on('callStateChange', this.callStateCallback);
      hilog.info(DOMAIN, TAG, 'Call state monitoring started');
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
        this.sendEvent({
          type: 'warning',
          operation: 'location',
          operationName: '定位',
          message: '定位服务未开启',
          timestamp: Date.now()
        });
        return;
      }

      // 监听定位服务状态变化
      geoLocationManager.on('locationEnabledChange', (isEnabled: boolean) => {
        this.sendEvent({
          type: 'permission_active_changed',
          operation: 'location',
          operationName: '定位',
          active: isEnabled,
          message: isEnabled ? '定位服务已开启' : '定位服务已关闭',
          timestamp: Date.now()
        });
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
  private sendEvent(eventData: Partial<PermissionEvent>): void {
    if (this.eventCallback) {
      const event = new PermissionEvent({
        ...eventData,
        timestamp: eventData.timestamp || Date.now()
      });
      this.eventCallback(event);
    }
  }
}
