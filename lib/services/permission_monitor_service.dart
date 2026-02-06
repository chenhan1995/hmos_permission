import 'dart:async';
import 'package:flutter/services.dart';

class PermissionEvent {
  final String type;
  final String? operation;
  final String? operationName;
  final String? packageName;
  final String? appName;
  final int? uid;
  final bool? active;
  final String? state;
  final int? stateCode;
  final String? message;
  final int timestamp;

  PermissionEvent({
    required this.type,
    this.operation,
    this.operationName,
    this.packageName,
    this.appName,
    this.uid,
    this.active,
    this.state,
    this.stateCode,
    this.message,
    required this.timestamp,
  });

  factory PermissionEvent.fromMap(Map<dynamic, dynamic> map) {
    return PermissionEvent(
      type: map['type'] as String? ?? 'unknown',
      operation: map['operation'] as String?,
      operationName: map['operationName'] as String?,
      packageName: map['packageName'] as String?,
      appName: map['appName'] as String?,
      uid: map['uid'] as int?,
      active: map['active'] as bool?,
      state: map['state'] as String?,
      stateCode: map['stateCode'] as int?,
      message: map['message'] as String?,
      timestamp: map['timestamp'] as int? ?? DateTime.now().millisecondsSinceEpoch,
    );
  }

  DateTime get dateTime => DateTime.fromMillisecondsSinceEpoch(timestamp);

  String get displayName {
    switch (type) {
      case 'permission_active_changed':
        return '${appName ?? packageName ?? "未知应用"} ${active == true ? "开始" : "停止"}使用${operationName ?? operation ?? "未知权限"}';
      case 'call_state_changed':
        return '通话状态: ${_getCallStateName(state)}';
      case 'permission_noted':
      case 'permission_self_noted':
      case 'permission_async_noted':
        return '权限使用: ${operationName ?? operation ?? "未知"}';
      case 'monitoring_started':
        return '监听已启动';
      case 'monitoring_stopped':
        return '监听已停止';
      case 'error':
        return '错误: ${message ?? "未知错误"}';
      case 'warning':
        return '警告: ${message ?? "未知警告"}';
      case 'camera_in_use':
        return '相机正在被使用';
      case 'camera_available':
        return '相机已释放';
      case 'audio_recording_started':
        return '检测到麦克风录音';
      case 'audio_recording_stopped':
        return '麦克风录音已停止';
      case 'sms_activity':
        return '检测到短信活动';
      default:
        return message ?? type;
    }
  }

  String _getCallStateName(String? state) {
    switch (state) {
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

  PermissionType? get permissionType {
    if (operation == null) return null;
    if (operation!.contains('camera')) return PermissionType.camera;
    if (operation!.contains('record_audio')) return PermissionType.microphone;
    if (operation!.contains('location')) return PermissionType.location;
    if (operation!.contains('sms')) return PermissionType.sms;
    if (operation!.contains('phone') || operation!.contains('call')) return PermissionType.phone;
    if (type == 'call_state_changed') return PermissionType.phone;
    return null;
  }
}

enum PermissionType {
  camera,
  microphone,
  location,
  phone,
  sms,
}

extension PermissionTypeExtension on PermissionType {
  String get displayName {
    switch (this) {
      case PermissionType.camera:
        return '相机';
      case PermissionType.microphone:
        return '麦克风';
      case PermissionType.location:
        return '定位';
      case PermissionType.phone:
        return '通话';
      case PermissionType.sms:
        return '短信';
    }
  }

  String get iconName {
    switch (this) {
      case PermissionType.camera:
        return 'camera_alt';
      case PermissionType.microphone:
        return 'mic';
      case PermissionType.location:
        return 'location_on';
      case PermissionType.phone:
        return 'phone';
      case PermissionType.sms:
        return 'sms';
    }
  }
}

class SystemInfo {
  final int sdkVersion;
  final String release;
  final String manufacturer;
  final String model;
  final String brand;
  final String device;
  final bool isHarmonyOS;
  final String harmonyVersion;

  SystemInfo({
    required this.sdkVersion,
    required this.release,
    required this.manufacturer,
    required this.model,
    required this.brand,
    required this.device,
    required this.isHarmonyOS,
    required this.harmonyVersion,
  });

  factory SystemInfo.fromMap(Map<dynamic, dynamic> map) {
    return SystemInfo(
      sdkVersion: map['sdkVersion'] as int? ?? 0,
      release: map['release'] as String? ?? 'unknown',
      manufacturer: map['manufacturer'] as String? ?? 'unknown',
      model: map['model'] as String? ?? 'unknown',
      brand: map['brand'] as String? ?? 'unknown',
      device: map['device'] as String? ?? 'unknown',
      isHarmonyOS: map['isHarmonyOS'] as bool? ?? false,
      harmonyVersion: map['harmonyVersion'] as String? ?? 'unknown',
    );
  }

  String get osName => isHarmonyOS ? 'HarmonyOS $harmonyVersion' : 'Android $release (API $sdkVersion)';
}

class ActivePermissionUsage {
  final String packageName;
  final String appName;
  final String operation;
  final String operationName;
  final bool active;

  ActivePermissionUsage({
    required this.packageName,
    required this.appName,
    required this.operation,
    required this.operationName,
    required this.active,
  });

  factory ActivePermissionUsage.fromMap(Map<dynamic, dynamic> map) {
    return ActivePermissionUsage(
      packageName: map['packageName'] as String? ?? '',
      appName: map['appName'] as String? ?? '',
      operation: map['operation'] as String? ?? '',
      operationName: map['operationName'] as String? ?? '',
      active: map['active'] as bool? ?? false,
    );
  }
}

class PermissionMonitorService {
  static const MethodChannel _methodChannel = MethodChannel('com.example.hmos_permission/method');
  static const EventChannel _eventChannel = EventChannel('com.example.hmos_permission/events');

  static PermissionMonitorService? _instance;
  static PermissionMonitorService get instance {
    _instance ??= PermissionMonitorService._();
    return _instance!;
  }

  PermissionMonitorService._();

  StreamSubscription? _eventSubscription;
  final StreamController<PermissionEvent> _eventController = StreamController<PermissionEvent>.broadcast();

  Stream<PermissionEvent> get eventStream => _eventController.stream;

  bool _isMonitoring = false;
  bool get isMonitoring => _isMonitoring;

  Future<void> startMonitoring() async {
    if (_isMonitoring) return;

    try {
      _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
        (dynamic event) {
          if (event is Map) {
            final permissionEvent = PermissionEvent.fromMap(event);
            _eventController.add(permissionEvent);
            
            if (permissionEvent.type == 'monitoring_started') {
              _isMonitoring = true;
            } else if (permissionEvent.type == 'monitoring_stopped') {
              _isMonitoring = false;
            }
          }
        },
        onError: (error) {
          _eventController.addError(error);
        },
      );

      await _methodChannel.invokeMethod('startMonitoring');
      _isMonitoring = true;
    } catch (e) {
      _eventController.addError(e);
      rethrow;
    }
  }

  Future<void> stopMonitoring() async {
    if (!_isMonitoring) return;

    try {
      await _methodChannel.invokeMethod('stopMonitoring');
      _isMonitoring = false;
      await _eventSubscription?.cancel();
      _eventSubscription = null;
    } catch (e) {
      _eventController.addError(e);
      rethrow;
    }
  }

  Future<SystemInfo> getSystemInfo() async {
    try {
      final result = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>('getSystemInfo');
      if (result != null) {
        return SystemInfo.fromMap(result);
      }
      throw Exception('Failed to get system info');
    } catch (e) {
      rethrow;
    }
  }

  Future<Map<String, dynamic>> checkPermissionStatus(String type) async {
    try {
      final result = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
        'checkPermissionStatus',
        {'type': type},
      );
      if (result != null) {
        return Map<String, dynamic>.from(result);
      }
      throw Exception('Failed to check permission status');
    } catch (e) {
      rethrow;
    }
  }

  Future<List<ActivePermissionUsage>> getActivePermissionUsage() async {
    try {
      final result = await _methodChannel.invokeMethod<List<dynamic>>('getActivePermissionUsage');
      if (result != null) {
        return result
            .whereType<Map<dynamic, dynamic>>()
            .map((e) => ActivePermissionUsage.fromMap(e))
            .toList();
      }
      return [];
    } catch (e) {
      rethrow;
    }
  }

  Future<bool> isHarmonyOS() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('isHarmonyOS');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  Future<bool> hasUsageStatsPermission() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('hasUsageStatsPermission');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  Future<void> openUsageStatsSettings() async {
    try {
      await _methodChannel.invokeMethod('openUsageStatsSettings');
    } catch (e) {
      rethrow;
    }
  }

  Future<void> startForegroundService() async {
    try {
      await _methodChannel.invokeMethod('startForegroundService');
      _isMonitoring = true;
    } catch (e) {
      rethrow;
    }
  }

  Future<void> stopForegroundService() async {
    try {
      await _methodChannel.invokeMethod('stopForegroundService');
      _isMonitoring = false;
    } catch (e) {
      rethrow;
    }
  }

  Future<bool> isServiceRunning() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('isServiceRunning');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  Future<void> setAutoStartOnBoot(bool enabled) async {
    try {
      await _methodChannel.invokeMethod('setAutoStartOnBoot', {'enabled': enabled});
    } catch (e) {
      rethrow;
    }
  }

  Future<bool> isAutoStartOnBoot() async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('isAutoStartOnBoot');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  Future<List<ActivePermissionUsage>> getRecentPermissionUsage({int minutes = 5}) async {
    try {
      final result = await _methodChannel.invokeMethod<List<dynamic>>(
        'getRecentPermissionUsage',
        {'minutes': minutes},
      );
      if (result != null) {
        return result
            .whereType<Map<dynamic, dynamic>>()
            .map((e) => ActivePermissionUsage.fromMap(e))
            .toList();
      }
      return [];
    } catch (e) {
      rethrow;
    }
  }

  void dispose() {
    _eventSubscription?.cancel();
    _eventController.close();
  }
}
