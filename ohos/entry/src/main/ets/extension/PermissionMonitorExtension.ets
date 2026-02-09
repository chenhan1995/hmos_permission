import { AppServiceExtensionAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { PermissionMonitorService } from '../services/PermissionMonitorService';
import { PermissionEvent } from '../model/PermissionModel';

const TAG: string = 'PermissionMonitorExtension';
const DOMAIN: number = 0x0000;

/**
 * 权限监听后台服务扩展
 * 用于在后台持续监听权限使用情况
 */
export default class PermissionMonitorExtension extends AppServiceExtensionAbility {
  private monitorService: PermissionMonitorService = new PermissionMonitorService();

  onCreate(want: Want): void {
    hilog.info(DOMAIN, TAG, 'PermissionMonitorExtension onCreate');
  }

  onRequest(want: Want, startId: number): void {
    hilog.info(DOMAIN, TAG, 'PermissionMonitorExtension onRequest, startId: %{public}d', startId);

    const action = want.parameters?.['action'] as string;

    if (action === 'start') {
      this.startMonitoring();
    } else if (action === 'stop') {
      this.stopMonitoring();
    }
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'PermissionMonitorExtension onDestroy');
    this.stopMonitoring();
  }

  private async startMonitoring(): Promise<void> {
    try {
      await this.monitorService.startMonitoring((event: PermissionEvent) => {
        hilog.info(DOMAIN, TAG, 'Permission event: %{public}s', event.displayName);
        // 可以在这里发送通知或更新UI
      });
      hilog.info(DOMAIN, TAG, 'Background monitoring started');
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to start background monitoring: %{public}s', JSON.stringify(error));
    }
  }

  private async stopMonitoring(): Promise<void> {
    try {
      await this.monitorService.stopMonitoring();
      hilog.info(DOMAIN, TAG, 'Background monitoring stopped');
    } catch (error) {
      hilog.error(DOMAIN, TAG, 'Failed to stop background monitoring: %{public}s', JSON.stringify(error));
    }
  }
}
