import { PermissionMonitorService } from '../services/PermissionMonitorService';
import { PermissionEvent, PermissionType } from '../model/PermissionModel';

@Entry
@Component
struct PermissionMonitorPage {
  @State isMonitoring: boolean = false;
  @State events: PermissionEvent[] = [];
  @State selectedType: PermissionType | undefined = undefined;
  private monitorService: PermissionMonitorService = new PermissionMonitorService();

  build() {
    Column() {
      // Filter Tabs
      Scroll() {
        Row() {
          this.FilterChip('全部', undefined)
          this.FilterChip('相机', PermissionType.CAMERA)
          this.FilterChip('麦克风', PermissionType.MICROPHONE)
          this.FilterChip('定位', PermissionType.LOCATION)
          this.FilterChip('通话', PermissionType.PHONE)
          this.FilterChip('短信', PermissionType.SMS)
        }
        .padding({ left: 16, right: 16 })
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')
      .height(56)
      .backgroundColor('#FFFFFF')

      // Filtered Events List
      List() {
        ForEach(this.getFilteredEvents(), (event: PermissionEvent, index: number) => {
          ListItem() {
            Column() {
              Row() {
                this.PermissionIcon(event.permissionType)
                Column() {
                  Text(event.displayName)
                    .fontSize(14)
                    .fontColor('#333333')
                    .maxLines(2)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(event.timeString)
                    .fontSize(12)
                    .fontColor('#999999')
                    .margin({ top: 4 })
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)
                .margin({ left: 12 })

                if (event.active !== undefined) {
                  Column() {
                    Circle()
                      .width(10)
                      .height(10)
                      .fill(event.active ? '#00C853' : '#9E9E9E')
                    Text(event.active ? '活跃' : '停止')
                      .fontSize(10)
                      .fontColor(event.active ? '#00C853' : '#9E9E9E')
                      .margin({ top: 2 })
                  }
                }
              }
              .width('100%')
              .padding(16)
            }
          }
          .backgroundColor('#FFFFFF')
          .borderRadius(8)
          .margin({ bottom: 8, left: 16, right: 16 })
        })
      }
      .width('100%')
      .layoutWeight(1)
      .padding({ top: 8 })

      // Empty State
      if (this.getFilteredEvents().length === 0) {
        Column() {
          Text('暂无监听事件')
            .fontSize(16)
            .fontColor('#999999')
          Text(this.isMonitoring ? '等待权限使用事件...' : '请先开始监听')
            .fontSize(14)
            .fontColor('#CCCCCC')
            .margin({ top: 8 })
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F1F3F5')
  }

  @Builder
  FilterChip(label: string, type: PermissionType | undefined) {
    Text(label)
      .fontSize(14)
      .fontColor(this.selectedType === type ? '#FFFFFF' : '#666666')
      .backgroundColor(this.selectedType === type ? '#007DFF' : '#F1F3F5')
      .borderRadius(16)
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .margin({ right: 8 })
      .onClick(() => {
        this.selectedType = type;
      })
  }

  @Builder
  PermissionIcon(type: PermissionType | undefined) {
    Column() {
      Image(this.getIconForType(type))
        .width(24)
        .height(24)
        .fillColor(this.getColorForType(type))
    }
    .width(40)
    .height(40)
    .backgroundColor(this.getBackgroundColorForType(type))
    .borderRadius(20)
    .justifyContent(FlexAlign.Center)
  }

  getFilteredEvents(): PermissionEvent[] {
    if (this.selectedType === undefined) {
      return this.events;
    }
    return this.events.filter((event) => event.permissionType === this.selectedType);
  }

  getIconForType(type: PermissionType | undefined): Resource {
    switch (type) {
      case PermissionType.CAMERA:
        return $r('sys.media.ohos_ic_public_camera');
      case PermissionType.MICROPHONE:
        return $r('sys.media.ohos_ic_public_voice');
      case PermissionType.LOCATION:
        return $r('sys.media.ohos_ic_public_location');
      case PermissionType.PHONE:
        return $r('sys.media.ohos_ic_public_phone');
      case PermissionType.SMS:
        return $r('sys.media.ohos_ic_public_message');
      default:
        return $r('sys.media.ohos_ic_public_more');
    }
  }

  getColorForType(type: PermissionType | undefined): string {
    switch (type) {
      case PermissionType.CAMERA:
        return '#2196F3';
      case PermissionType.MICROPHONE:
        return '#FF5722';
      case PermissionType.LOCATION:
        return '#4CAF50';
      case PermissionType.PHONE:
        return '#9C27B0';
      case PermissionType.SMS:
        return '#FF9800';
      default:
        return '#607D8B';
    }
  }

  getBackgroundColorForType(type: PermissionType | undefined): string {
    switch (type) {
      case PermissionType.CAMERA:
        return '#E3F2FD';
      case PermissionType.MICROPHONE:
        return '#FBE9E7';
      case PermissionType.LOCATION:
        return '#E8F5E9';
      case PermissionType.PHONE:
        return '#F3E5F5';
      case PermissionType.SMS:
        return '#FFF3E0';
      default:
        return '#ECEFF1';
    }
  }
}
