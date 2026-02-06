import 'dart:async';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import '../services/permission_monitor_service.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with TickerProviderStateMixin {
  final PermissionMonitorService _monitorService = PermissionMonitorService.instance;
  
  SystemInfo? _systemInfo;
  bool _isMonitoring = false;
  bool _hasUsageStatsPermission = false;
  final List<PermissionEvent> _events = [];
  StreamSubscription<PermissionEvent>? _eventSubscription;
  
  late TabController _tabController;
  
  final Map<PermissionType, bool> _permissionStatus = {
    PermissionType.camera: false,
    PermissionType.microphone: false,
    PermissionType.location: false,
    PermissionType.phone: false,
    PermissionType.sms: false,
  };

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    _loadSystemInfo();
    _checkPermissions();
    _checkUsageStatsPermission();
    _subscribeToEvents();
  }

  Future<void> _checkUsageStatsPermission() async {
    final hasPermission = await _monitorService.hasUsageStatsPermission();
    setState(() {
      _hasUsageStatsPermission = hasPermission;
    });
  }

  @override
  void dispose() {
    _eventSubscription?.cancel();
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _loadSystemInfo() async {
    try {
      final info = await _monitorService.getSystemInfo();
      setState(() {
        _systemInfo = info;
      });
    } catch (e) {
      debugPrint('Error loading system info: $e');
    }
  }

  Future<void> _checkPermissions() async {
    final permissions = [
      Permission.camera,
      Permission.microphone,
      Permission.location,
      Permission.phone,
      Permission.sms,
    ];

    for (var permission in permissions) {
      final status = await permission.status;
      setState(() {
        switch (permission) {
          case Permission.camera:
            _permissionStatus[PermissionType.camera] = status.isGranted;
            break;
          case Permission.microphone:
            _permissionStatus[PermissionType.microphone] = status.isGranted;
            break;
          case Permission.location:
            _permissionStatus[PermissionType.location] = status.isGranted;
            break;
          case Permission.phone:
            _permissionStatus[PermissionType.phone] = status.isGranted;
            break;
          case Permission.sms:
            _permissionStatus[PermissionType.sms] = status.isGranted;
            break;
          default:
            break;
        }
      });
    }
  }

  void _subscribeToEvents() {
    _eventSubscription = _monitorService.eventStream.listen(
      (event) {
        setState(() {
          _events.insert(0, event);
          if (_events.length > 100) {
            _events.removeLast();
          }
        });
      },
      onError: (error) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('错误: $error')),
        );
      },
    );
  }

  Future<void> _toggleMonitoring() async {
    try {
      if (_isMonitoring) {
        await _monitorService.stopMonitoring();
      } else {
        await _monitorService.startMonitoring();
      }
      setState(() {
        _isMonitoring = !_isMonitoring;
      });
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('操作失败: $e')),
      );
    }
  }

  Future<void> _requestPermission(Permission permission) async {
    final status = await permission.request();
    _checkPermissions();
    
    if (status.isPermanentlyDenied) {
      if (mounted) {
        showDialog(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('权限被拒绝'),
            content: const Text('请在系统设置中手动开启权限'),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('取消'),
              ),
              TextButton(
                onPressed: () {
                  Navigator.pop(context);
                  openAppSettings();
                },
                child: const Text('去设置'),
              ),
            ],
          ),
        );
      }
    }
  }

  Future<void> _requestAllPermissions() async {
    await [
      Permission.camera,
      Permission.microphone,
      Permission.location,
      Permission.phone,
      Permission.sms,
    ].request();
    _checkPermissions();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('权限监听器'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(icon: Icon(Icons.dashboard), text: '概览'),
            Tab(icon: Icon(Icons.security), text: '权限'),
            Tab(icon: Icon(Icons.history), text: '事件'),
          ],
        ),
        actions: [
          IconButton(
            icon: Icon(_isMonitoring ? Icons.stop : Icons.play_arrow),
            onPressed: _toggleMonitoring,
            tooltip: _isMonitoring ? '停止监听' : '开始监听',
          ),
        ],
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          _buildOverviewTab(),
          _buildPermissionsTab(),
          _buildEventsTab(),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _toggleMonitoring,
        icon: Icon(_isMonitoring ? Icons.stop : Icons.play_arrow),
        label: Text(_isMonitoring ? '停止监听' : '开始监听'),
        backgroundColor: _isMonitoring ? Colors.red : Colors.green,
      ),
    );
  }

  Widget _buildOverviewTab() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSystemInfoCard(),
          const SizedBox(height: 16),
          _buildUsageStatsPermissionCard(),
          const SizedBox(height: 16),
          _buildMonitoringStatusCard(),
          const SizedBox(height: 16),
          _buildQuickPermissionStatusCard(),
        ],
      ),
    );
  }

  Widget _buildUsageStatsPermissionCard() {
    return Card(
      color: _hasUsageStatsPermission ? Colors.green.shade50 : Colors.orange.shade50,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  _hasUsageStatsPermission ? Icons.check_circle : Icons.warning,
                  color: _hasUsageStatsPermission ? Colors.green : Colors.orange,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '使用情况访问权限',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              _hasUsageStatsPermission
                  ? '已授权，可以监听其他应用的权限使用'
                  : '未授权，无法监听其他应用的权限使用。这是监听第三方应用的必要权限。',
              style: TextStyle(
                color: _hasUsageStatsPermission ? Colors.green.shade700 : Colors.orange.shade700,
                fontSize: 13,
              ),
            ),
            if (!_hasUsageStatsPermission) ...[
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: () async {
                    await _monitorService.openUsageStatsSettings();
                    // Check again after a delay
                    Future.delayed(const Duration(seconds: 2), () {
                      _checkUsageStatsPermission();
                    });
                  },
                  icon: const Icon(Icons.settings),
                  label: const Text('去授权'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.orange,
                    foregroundColor: Colors.white,
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildSystemInfoCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  _systemInfo?.isHarmonyOS == true 
                      ? Icons.phone_android 
                      : Icons.android,
                  color: _systemInfo?.isHarmonyOS == true 
                      ? Colors.red 
                      : Colors.green,
                ),
                const SizedBox(width: 8),
                Text(
                  '系统信息',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ],
            ),
            const Divider(),
            if (_systemInfo != null) ...[
              _buildInfoRow('操作系统', _systemInfo!.osName),
              _buildInfoRow('设备型号', '${_systemInfo!.brand} ${_systemInfo!.model}'),
              _buildInfoRow('制造商', _systemInfo!.manufacturer),
              if (_systemInfo!.isHarmonyOS)
                _buildInfoRow('鸿蒙版本', _systemInfo!.harmonyVersion),
            ] else
              const Center(child: CircularProgressIndicator()),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(color: Colors.grey)),
          Text(value, style: const TextStyle(fontWeight: FontWeight.w500)),
        ],
      ),
    );
  }

  Widget _buildMonitoringStatusCard() {
    return Card(
      color: _isMonitoring ? Colors.green.shade50 : Colors.grey.shade100,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Container(
              width: 12,
              height: 12,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: _isMonitoring ? Colors.green : Colors.grey,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _isMonitoring ? '监听中' : '未监听',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  Text(
                    _isMonitoring 
                        ? '正在监听系统和应用的权限使用情况' 
                        : '点击下方按钮开始监听',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
            Text(
              '${_events.length}',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(width: 4),
            const Text('事件'),
          ],
        ),
      ),
    );
  }

  Widget _buildQuickPermissionStatusCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '权限状态',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const Divider(),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: PermissionType.values.map((type) {
                final granted = _permissionStatus[type] ?? false;
                return Chip(
                  avatar: Icon(
                    _getPermissionIcon(type),
                    size: 18,
                    color: granted ? Colors.green : Colors.red,
                  ),
                  label: Text(type.displayName),
                  backgroundColor: granted 
                      ? Colors.green.shade50 
                      : Colors.red.shade50,
                );
              }).toList(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPermissionsTab() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        ElevatedButton.icon(
          onPressed: _requestAllPermissions,
          icon: const Icon(Icons.security),
          label: const Text('请求所有权限'),
          style: ElevatedButton.styleFrom(
            minimumSize: const Size.fromHeight(48),
          ),
        ),
        const SizedBox(height: 16),
        ...PermissionType.values.map((type) => _buildPermissionCard(type)),
      ],
    );
  }

  Widget _buildPermissionCard(PermissionType type) {
    final granted = _permissionStatus[type] ?? false;
    
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: granted ? Colors.green : Colors.red,
          child: Icon(
            _getPermissionIcon(type),
            color: Colors.white,
          ),
        ),
        title: Text(type.displayName),
        subtitle: Text(granted ? '已授权' : '未授权'),
        trailing: granted
            ? const Icon(Icons.check_circle, color: Colors.green)
            : ElevatedButton(
                onPressed: () => _requestPermission(_getPermissionFromType(type)),
                child: const Text('请求'),
              ),
      ),
    );
  }

  Widget _buildEventsTab() {
    if (_events.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.event_note,
              size: 64,
              color: Colors.grey.shade400,
            ),
            const SizedBox(height: 16),
            Text(
              '暂无事件',
              style: TextStyle(
                fontSize: 18,
                color: Colors.grey.shade600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              _isMonitoring ? '等待权限使用事件...' : '请先开始监听',
              style: TextStyle(color: Colors.grey.shade500),
            ),
          ],
        ),
      );
    }

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('共 ${_events.length} 条事件'),
              TextButton.icon(
                onPressed: () {
                  setState(() {
                    _events.clear();
                  });
                },
                icon: const Icon(Icons.clear_all),
                label: const Text('清空'),
              ),
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            itemCount: _events.length,
            itemBuilder: (context, index) {
              final event = _events[index];
              return _buildEventCard(event);
            },
          ),
        ),
      ],
    );
  }

  Widget _buildEventCard(PermissionEvent event) {
    final color = _getEventColor(event);
    
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: color.withOpacity(0.2),
          child: Icon(
            _getEventIcon(event),
            color: color,
            size: 20,
          ),
        ),
        title: Text(
          event.displayName,
          style: const TextStyle(fontSize: 14),
        ),
        subtitle: Text(
          _formatTime(event.dateTime),
          style: const TextStyle(fontSize: 12),
        ),
        trailing: event.active != null
            ? Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: event.active! ? Colors.green : Colors.grey,
                ),
              )
            : null,
      ),
    );
  }

  IconData _getPermissionIcon(PermissionType type) {
    switch (type) {
      case PermissionType.camera:
        return Icons.camera_alt;
      case PermissionType.microphone:
        return Icons.mic;
      case PermissionType.location:
        return Icons.location_on;
      case PermissionType.phone:
        return Icons.phone;
      case PermissionType.sms:
        return Icons.sms;
    }
  }

  Permission _getPermissionFromType(PermissionType type) {
    switch (type) {
      case PermissionType.camera:
        return Permission.camera;
      case PermissionType.microphone:
        return Permission.microphone;
      case PermissionType.location:
        return Permission.location;
      case PermissionType.phone:
        return Permission.phone;
      case PermissionType.sms:
        return Permission.sms;
    }
  }

  Color _getEventColor(PermissionEvent event) {
    if (event.type == 'error') return Colors.red;
    if (event.type == 'warning') return Colors.orange;
    if (event.type == 'monitoring_started') return Colors.green;
    if (event.type == 'monitoring_stopped') return Colors.orange;
    if (event.type == 'call_state_changed') return Colors.blue;
    if (event.type == 'camera_in_use' || event.type == 'camera_available') return Colors.purple;
    if (event.type == 'audio_recording_started' || event.type == 'audio_recording_stopped') return Colors.red;
    if (event.type == 'sms_activity') return Colors.orange;
    
    final permType = event.permissionType;
    if (permType == null) return Colors.grey;
    
    switch (permType) {
      case PermissionType.camera:
        return Colors.purple;
      case PermissionType.microphone:
        return Colors.red;
      case PermissionType.location:
        return Colors.blue;
      case PermissionType.phone:
        return Colors.green;
      case PermissionType.sms:
        return Colors.orange;
    }
  }

  IconData _getEventIcon(PermissionEvent event) {
    if (event.type == 'error') return Icons.error;
    if (event.type == 'warning') return Icons.warning;
    if (event.type == 'monitoring_started') return Icons.play_circle;
    if (event.type == 'monitoring_stopped') return Icons.stop_circle;
    if (event.type == 'call_state_changed') return Icons.phone;
    if (event.type == 'camera_in_use') return Icons.camera_alt;
    if (event.type == 'camera_available') return Icons.camera_alt_outlined;
    if (event.type == 'audio_recording_started') return Icons.mic;
    if (event.type == 'audio_recording_stopped') return Icons.mic_off;
    if (event.type == 'sms_activity') return Icons.sms;
    
    final permType = event.permissionType;
    if (permType == null) return Icons.info;
    
    return _getPermissionIcon(permType);
  }

  String _formatTime(DateTime time) {
    return '${time.hour.toString().padLeft(2, '0')}:'
        '${time.minute.toString().padLeft(2, '0')}:'
        '${time.second.toString().padLeft(2, '0')}';
  }
}
