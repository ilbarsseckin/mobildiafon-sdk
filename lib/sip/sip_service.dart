import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:sip_ua/sip_ua.dart';

/// MobilDiafon — İZOLE SIP servisi.
/// =============================================================================
/// Mevcut Socket.io/WebRTC akışına DOKUNMAZ. Yalnızca "SIP dünyası" binalarda
/// (IP paneller: Hikvision/DNAKE/Core/Akuvox) devreye girer. Asterisk'e
/// SIP-over-WebSocket (wss:8089) ile register olur; panel arayınca gelen çağrıyı
/// yakalar. Medya flutter_webrtc (zaten projede var).
///
/// Kapatma: SipService.instance.stop() → her şey durur, eski akış etkilenmez.
///
/// NOT: sip_ua ^1.0.0 için yazıldı. Sürüm farklıysa 1-2 imza değişebilir
/// (answer/buildCallOptions), yorumlarda işaretli.
class SipConfig {
  /// wss://mobildiafon.com:8089/ws  (IP değil, sertifika ismi!)
  final String wssUrl;
  /// SIP domain (Asterisk realm host), genelde mobildiafon.com
  final String domain;
  final String username;      // ör. d10 ya da b<bina>-d<daire>
  final String password;
  final String displayName;
  final List<Map<String, dynamic>> iceServers;

  const SipConfig({
    required this.wssUrl,
    required this.domain,
    required this.username,
    required this.password,
    this.displayName = 'MobilDiafon',
    this.iceServers = const [
      {'urls': 'stun:stun.l.google.com:19302'},
      {
        'urls': 'turn:128.140.127.151:3478',
        'username': 'diafonturn',
        'credential': 'turnpass2026',
      },
    ],
  });
}

/// Dışarıya sadeleştirilmiş olaylar.
abstract class SipEvents {
  void onSipRegistered(bool registered);        // Asterisk'e bağlandı/koptu
  void onSipIncoming(Call call, String caller); // panel arıyor -> ekranı aç
  void onSipEnded(String reason);               // çağrı bitti
}

class SipService implements SipUaHelperListener {
  SipService._();
  static final SipService instance = SipService._();

  final SIPUAHelper _helper = SIPUAHelper();
  SipConfig? _config;
  SipEvents? _events;
  bool _started = false;

  bool get isRegistered => _helper.registerState.state == RegistrationStateEnum.REGISTERED;
  SIPUAHelper get helper => _helper;

  void setEvents(SipEvents e) => _events = e;

  /// SIP dünyası binada login sonrası çağrılır. Eski akışa dokunmaz.
  void start(SipConfig config) {
    if (_started) return;
    _config = config;
    _helper.addSipUaHelperListener(this);

    final settings = UaSettings()
      ..webSocketUrl = config.wssUrl
      ..uri = 'sip:${config.username}@${config.domain}'
      ..authorizationUser = config.username
      ..password = config.password
      ..displayName = config.displayName
      ..userAgent = 'MobilDiafon/1.0'
      ..dtmfMode = DtmfMode.RFC2833
      ..transportType = TransportType.WS
      ..iceServers = config.iceServers
      ..register = true;

    // Bazı sürümlerde sertifika ayarı:
    settings.webSocketSettings.allowBadCertificate = false;

    _helper.start(settings);
    _started = true;
    if (kDebugMode) print('[SIP] start -> ${config.wssUrl} as ${config.username}');
  }

  /// Modülü tamamen kapat (güvenlik anahtarı). Eski akış etkilenmez.
  void stop() {
    if (!_started) return;
    try { _helper.stop(); } catch (_) {}
    try { _helper.removeSipUaHelperListener(this); } catch (_) {}
    _started = false;
    if (kDebugMode) print('[SIP] stop');
  }

  /// Gelen çağrıyı sesli+görüntülü cevapla. Renderer'lar UI'dan gelir.
  Future<void> answer(Call call) async {
    final opts = _helper.buildCallOptions(false); // false = video da açık
    call.answer(opts);
  }

  void hangup(Call call) {
    try { call.hangup(); } catch (_) {}
  }

  void reject(Call call) {
    try { call.hangup({'status_code': 603}); } catch (_) {}
  }

  /// Kapı aç (çağrı içi DTMF). Panelin DTMF-kapı kodu neyse onu gönder (ör. '#').
  void sendDoorDtmf(Call call, {String digits = '#'}) {
    try { call.sendDTMF(digits); } catch (_) {}
  }

  // ------------------- SipUaHelperListener -------------------

  @override
  void registrationStateChanged(RegistrationState state) {
    final reg = state.state == RegistrationStateEnum.REGISTERED;
    if (kDebugMode) print('[SIP] register: ${state.state}');
    _events?.onSipRegistered(reg);
  }

  @override
  void transportStateChanged(TransportState state) {
    if (kDebugMode) print('[SIP] transport: ${state.state}');
  }

  @override
  void callStateChanged(Call call, CallState state) {
    switch (state.state) {
      case CallStateEnum.CALL_INITIATION:
        if (call.direction == 'incoming' || call.direction == Direction.incoming) {
          final caller = call.remote_display_name?.isNotEmpty == true
              ? call.remote_display_name!
              : (call.remote_identity ?? 'Kapı');
          _events?.onSipIncoming(call, caller);
        }
        break;
      case CallStateEnum.ENDED:
      case CallStateEnum.FAILED:
        _events?.onSipEnded(state.cause?.toString() ?? 'ended');
        break;
      default:
        break;
    }
  }

  @override
  void onNewMessage(SIPMessageRequest msg) {}

  @override
  void onNewNotify(Notify ntf) {}

  // Bazı sürümlerde bu metod vardır; yoksa silebilirsin.
  @override
  void onNewReinvite(ReInvite event) {}
}
