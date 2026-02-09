import { router } from '@kit.ArkUI';
import { PermissionMonitorService } from '../services/PermissionMonitorService';
import { PermissionEvent, PermissionType } from '../model/PermissionModel';

@Entry
@Component
struct Index {
  @State isMonitoring: boolean = false;
  @State events: PermissionEvent[] = [];
  @State systemInfo: string = '';
  private monitorService: PermissionMonitorService = new PermissionMonitorService();

  aboutToAppear(): void {
    this.loadSystemInfo();
  }

  async loadSystemInfo(): Promise<void> {
    const info = await this.monitorService.getSystemInfo();
    this.systemInfo = `HarmonyOS NEXT API ${info.apiVersion}`;
  }

  async toggleMonitoring(): Promise<void> {
    if (this.isMonitoring) {
      await this.monitorService.stopMonitoring();
      this.isMonitoring = false;
    } else {
      const started = await this.monitorService.startMonitoring((event: PermissionEvent) => {
        this.events = [event, ...this.events.slice(0, 99)];
      });
      this.isMonitoring = started;
    }
  }

  build() {
    Column() {
      // Header
      Row() {
        Text('权限监听器')
          .fontSize(24)
          .fontWeight(FontWeight.Bold)
          .fontColor('#333333')
        Blank()
        Text(this.systemInfo)
          .fontSize(12)
          .fontColor('#666666')
      }
      .width('100%')
      .padding(16)
      .backgroundColor('#FFFFFF')

      // Status Card
      Column() {
        Row() {
          Circle()
            .width(12)
            .height(12)
            .fill(this.isMonitoring ? '#00C853' : '#9E9E9E')
          Text(this.isMonitoring ? '监听中' : '已停止')
            .fontSize(16)
            .fontColor(this.isMonitoring ? '#00C853' : '#9E9E9E')
            .margin({ left: 8 })
        }
        .margin({ bottom: 16 })

        Button(this.isMonitoring ? '停止监听' : '开始监听')
          .width('100%')
          .height(48)
          .backgroundColor(this.isMonitoring ? '#FF5252' : '#007DFF')
          .onClick(() => this.toggleMonitoring())
      }
      .width('100%')
      .padding(16)
      .margin({ top: 8, left: 16, right: 16 })
      .backgroundColor('#FFFFFF')
      .borderRadius(12)

      // Permission Types
      Text('监听类型')
        .fontSize(14)
        .fontColor('#666666')
        .width('100%')
        .padding({ left: 16, top: 16, bottom: 8 })

      Row() {
        ForEach(this.getPermissionTypes(), (item: PermissionTypeItem) => {
          Column() {
            Image(item.icon)
              .width(32)
              .height(32)
              .fillColor('#007DFF')
            Text(item.name)
              .fontSize(12)
              .fontColor('#333333')
              .margin({ top: 4 })
          }
          .width('20%')
          .padding(8)
        })
      }
      .width('100%')
      .padding({ left: 8, right: 8 })

      // Events List
      Text('监听事件')
        .fontSize(14)
        .fontColor('#666666')
        .width('100%')
        .padding({ left: 16, top: 16, bottom: 8 })

      List() {
        ForEach(this.events, (event: PermissionEvent, index: number) => {
          ListItem() {
            Row() {
              Column() {
                Text(event.displayName)
                  .fontSize(14)
                  .fontColor('#333333')
                Text(event.timeString)
                  .fontSize(12)
                  .fontColor('#999999')
                  .margin({ top: 4 })
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)

              if (event.active !== undefined) {
                Circle()
                  .width(8)
                  .height(8)
                  .fill(event.active ? '#00C853' : '#9E9E9E')
              }
            }
            .width('100%')
            .padding(12)
          }
          .backgroundColor('#FFFFFF')
          .margin({ bottom: 1 })
        })
      }
      .width('100%')
      .layoutWeight(1)
      .padding({ left: 16, right: 16 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F1F3F5')
  }

  getPermissionTypes(): PermissionTypeItem[] {
    return [
      { name: '相机', icon: $r('sys.media.ohos_ic_public_camera') },
      { name: '麦克风', icon: $r('sys.media.ohos_ic_public_voice') },
      { name: '定位', icon: $r('sys.media.ohos_ic_public_location') },
      { name: '通话', icon: $r('sys.media.ohos_ic_public_phone') },
      { name: '短信', icon: $r('sys.media.ohos_ic_public_email') }
    ];
  }
}

interface PermissionTypeItem {
  name: string;
  icon: Resource;
}
