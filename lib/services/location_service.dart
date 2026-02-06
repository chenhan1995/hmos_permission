import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';

class LocationInfo {
  final double latitude;
  final double longitude;
  final double? altitude;
  final double? accuracy;
  final double? speed;
  final double? heading;
  final DateTime timestamp;

  LocationInfo({
    required this.latitude,
    required this.longitude,
    this.altitude,
    this.accuracy,
    this.speed,
    this.heading,
    required this.timestamp,
  });

  factory LocationInfo.fromPosition(Position position) {
    return LocationInfo(
      latitude: position.latitude,
      longitude: position.longitude,
      altitude: position.altitude,
      accuracy: position.accuracy,
      speed: position.speed,
      heading: position.heading,
      timestamp: position.timestamp,
    );
  }

  @override
  String toString() {
    return '纬度: ${latitude.toStringAsFixed(6)}, 经度: ${longitude.toStringAsFixed(6)}';
  }
}

enum LocationServiceStatus {
  disabled,
  permissionDenied,
  permissionDeniedForever,
  ready,
  listening,
}

class LocationService {
  static LocationService? _instance;
  static LocationService get instance {
    _instance ??= LocationService._();
    return _instance!;
  }

  LocationService._();

  StreamSubscription<Position>? _positionSubscription;
  StreamSubscription<ServiceStatus>? _serviceStatusSubscription;

  final StreamController<LocationInfo> _locationController = StreamController<LocationInfo>.broadcast();
  final StreamController<LocationServiceStatus> _statusController = StreamController<LocationServiceStatus>.broadcast();
  final StreamController<bool> _permissionController = StreamController<bool>.broadcast();

  Stream<LocationInfo> get locationStream => _locationController.stream;
  Stream<LocationServiceStatus> get statusStream => _statusController.stream;
  Stream<bool> get permissionStream => _permissionController.stream;

  LocationInfo? _lastLocation;
  LocationInfo? get lastLocation => _lastLocation;

  LocationServiceStatus _currentStatus = LocationServiceStatus.disabled;
  LocationServiceStatus get currentStatus => _currentStatus;

  bool _isListening = false;
  bool get isListening => _isListening;

  Future<LocationServiceStatus> checkStatus() async {
    bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      _updateStatus(LocationServiceStatus.disabled);
      return LocationServiceStatus.disabled;
    }

    LocationPermission permission = await Geolocator.checkPermission();
    switch (permission) {
      case LocationPermission.denied:
        _updateStatus(LocationServiceStatus.permissionDenied);
        return LocationServiceStatus.permissionDenied;
      case LocationPermission.deniedForever:
        _updateStatus(LocationServiceStatus.permissionDeniedForever);
        return LocationServiceStatus.permissionDeniedForever;
      case LocationPermission.whileInUse:
      case LocationPermission.always:
        if (_isListening) {
          _updateStatus(LocationServiceStatus.listening);
          return LocationServiceStatus.listening;
        }
        _updateStatus(LocationServiceStatus.ready);
        return LocationServiceStatus.ready;
      case LocationPermission.unableToDetermine:
        _updateStatus(LocationServiceStatus.permissionDenied);
        return LocationServiceStatus.permissionDenied;
    }
  }

  Future<bool> requestPermission() async {
    LocationPermission permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }

    final granted = permission == LocationPermission.whileInUse ||
        permission == LocationPermission.always;
    _permissionController.add(granted);
    await checkStatus();
    return granted;
  }

  Future<void> openLocationSettings() async {
    await Geolocator.openLocationSettings();
  }

  Future<void> openAppSettings() async {
    await Geolocator.openAppSettings();
  }

  Future<LocationInfo?> getCurrentLocation() async {
    try {
      final status = await checkStatus();
      if (status == LocationServiceStatus.disabled ||
          status == LocationServiceStatus.permissionDenied ||
          status == LocationServiceStatus.permissionDeniedForever) {
        return null;
      }

      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
          timeLimit: Duration(seconds: 10),
        ),
      );

      _lastLocation = LocationInfo.fromPosition(position);
      return _lastLocation;
    } catch (e) {
      debugPrint('Error getting current location: $e');
      return null;
    }
  }

  Future<void> startLocationListening({
    LocationAccuracy accuracy = LocationAccuracy.high,
    int distanceFilter = 10,
  }) async {
    if (_isListening) return;

    final status = await checkStatus();
    if (status == LocationServiceStatus.disabled ||
        status == LocationServiceStatus.permissionDenied ||
        status == LocationServiceStatus.permissionDeniedForever) {
      throw Exception('定位服务不可用或权限不足');
    }

    _isListening = true;
    _updateStatus(LocationServiceStatus.listening);

    final locationSettings = LocationSettings(
      accuracy: accuracy,
      distanceFilter: distanceFilter,
    );

    _positionSubscription = Geolocator.getPositionStream(
      locationSettings: locationSettings,
    ).listen(
          (Position position) {
        _lastLocation = LocationInfo.fromPosition(position);
        _locationController.add(_lastLocation!);
      },
      onError: (error) {
        debugPrint('Location stream error: $error');
        _locationController.addError(error);
      },
    );

    _startServiceStatusListening();
  }

  void _startServiceStatusListening() {
    _serviceStatusSubscription?.cancel();
    _serviceStatusSubscription = Geolocator.getServiceStatusStream().listen(
          (ServiceStatus status) {
        if (status == ServiceStatus.disabled) {
          _updateStatus(LocationServiceStatus.disabled);
          _permissionController.add(false);
        } else {
          checkStatus();
        }
      },
    );
  }

  Future<void> stopLocationListening() async {
    if (!_isListening) return;

    await _positionSubscription?.cancel();
    _positionSubscription = null;

    await _serviceStatusSubscription?.cancel();
    _serviceStatusSubscription = null;

    _isListening = false;
    await checkStatus();
  }

  void _updateStatus(LocationServiceStatus status) {
    if (_currentStatus != status) {
      _currentStatus = status;
      _statusController.add(status);
    }
  }

  Future<void> startPermissionMonitoring() async {
    _startServiceStatusListening();

    Timer.periodic(const Duration(seconds: 3), (timer) async {
      if (!_isListening) {
        timer.cancel();
        return;
      }

      final permission = await Geolocator.checkPermission();
      final granted = permission == LocationPermission.whileInUse ||
          permission == LocationPermission.always;
      _permissionController.add(granted);
    });
  }

  void dispose() {
    _positionSubscription?.cancel();
    _serviceStatusSubscription?.cancel();
    _locationController.close();
    _statusController.close();
    _permissionController.close();
  }
}
