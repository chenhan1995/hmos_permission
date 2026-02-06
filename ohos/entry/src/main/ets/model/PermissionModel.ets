/**
 * 权限事件模型
 */
export class PermissionEvent {
  type: string = '';
  operation?: string;
  operationName?: string;
  packageName?: string;
  appName?: string;
  uid?: number;
  active?: boolean;
  state?: string;
  stateCode?: number;
  message?: string;
  timestamp: number = 0;

  constructor(data?: Partial<PermissionEvent>) {
    if (data) {
      Object.assign(this, data);
    }
  }

  get displayName(): string {
    switch (this.type) {
      case 'permission_active_changed':
        const app = this.appName || this.packageName || '未知应用';
        const action = this.active ? '开始' : '停止';
        const perm = this.operationName || this.operation || '未知权限';
        return `${app} ${action}使用${perm}`;
      case 'call_state_changed':
        return `通话状态: ${this.getCallStateName()}`;
      case 'monitoring_started':
        return '监听已启动';
      case 'monitoring_stopped':
        return '监听已停止';
      case 'error':
        return `错误: ${this.message || '未知错误'}`;
      case 'warning':
        return `警告: ${this.message || '未知警告'}`;
      case 'camera_in_use':
        return '相机正在被使用';
      case 'camera_available':
        return '相机已释放';
      case 'audio_recording_started':
        return '检测到麦克风录音';
      case 'audio_recording_stopped':
        return '麦克风录音已停止';
      default:
        return this.message || this.type;
    }
  }

  get timeString(): string {
    const date = new Date(this.timestamp);
    return `${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}:${date.getSeconds().toString().padStart(2, '0')}`;
  }

  private getCallStateName(): string {
    switch (this.state) {
      case 'idle':
        return '空闲';
      case 'ringing':
        return '响铃中';
      case 'offhook':
        return '通话中';
      default:
        return '未知';
    }
  }

  get permissionType(): PermissionType | undefined {
    if (!this.operation) return undefined;
    if (this.operation.includes('camera')) return PermissionType.CAMERA;
    if (this.operation.includes('microphone') || this.operation.includes('record_audio')) return PermissionType.MICROPHONE;
    if (this.operation.includes('location')) return PermissionType.LOCATION;
    if (this.operation.includes('sms') || this.operation.includes('message')) return PermissionType.SMS;
    if (this.operation.includes('phone') || this.operation.includes('call')) return PermissionType.PHONE;
    if (this.type === 'call_state_changed') return PermissionType.PHONE;
    return undefined;
  }
}

/**
 * 权限类型枚举
 */
export enum PermissionType {
  CAMERA = 'camera',
  MICROPHONE = 'microphone',
  LOCATION = 'location',
  PHONE = 'phone',
  SMS = 'sms'
}

/**
 * 系统信息模型
 */
export class SystemInfo {
  apiVersion: number = 0;
  deviceType: string = '';
  manufacturer: string = '';
  brand: string = '';
  model: string = '';
  osFullName: string = '';

  constructor(data?: Partial<SystemInfo>) {
    if (data) {
      Object.assign(this, data);
    }
  }

  get displayName(): string {
    return `HarmonyOS NEXT (API ${this.apiVersion})`;
  }
}

/**
 * 活跃权限使用记录
 */
export class ActivePermissionUsage {
  packageName: string = '';
  appName: string = '';
  operation: string = '';
  operationName: string = '';
  active: boolean = false;

  constructor(data?: Partial<ActivePermissionUsage>) {
    if (data) {
      Object.assign(this, data);
    }
  }
}
