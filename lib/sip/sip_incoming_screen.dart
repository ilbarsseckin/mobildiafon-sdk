import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:sip_ua/sip_ua.dart';
import 'sip_service.dart';

/// SIP gelen çağrı ekranı — İZOLE. Mevcut çağrı ekranına dokunmaz.
/// Panel (Core/Hikvision/DNAKE/Akuvox) arayınca açılır: kapı kamerası + ses,
/// Cevapla / Reddet / Kapı Aç.
class SipIncomingScreen extends StatefulWidget {
  final Call call;
  final String caller;
  const SipIncomingScreen({super.key, required this.call, required this.caller});

  @override
  State<SipIncomingScreen> createState() => _SipIncomingScreenState();
}

class _SipIncomingScreenState extends State<SipIncomingScreen> implements SipUaHelperListener {
  final _remote = RTCVideoRenderer();
  bool _answered = false;
  bool _ready = false;

  @override
  void initState() {
    super.initState();
    _init();
    SipService.instance.helper.addSipUaHelperListener(this);
  }

  Future<void> _init() async {
    await _remote.initialize();
    if (mounted) setState(() => _ready = true);
  }

  Future<void> _answer() async {
    if (_answered) return;
    _answered = true;
    await SipService.instance.answer(widget.call);
    if (mounted) setState(() {});
  }

  void _reject() {
    SipService.instance.reject(widget.call);
    _close();
  }

  void _hangup() {
    SipService.instance.hangup(widget.call);
    _close();
  }

  void _openDoor() {
    // Panelin çağrı-içi DTMF kapı kodu (marka ayarına göre; ör. '#').
    SipService.instance.sendDoorDtmf(widget.call, digits: '#');
  }

  void _close() {
    if (mounted) Navigator.of(context).maybePop();
  }

  // ---- SIP medya akışı ----
  @override
  void callStateChanged(Call call, CallState state) {
    if (call != widget.call) return;
    if (state.state == CallStateEnum.STREAM && state.stream != null) {
      // Panelin videosu (uzak akış)
      if (state.originator == 'remote') {
        _remote.srcObject = state.stream;
        if (mounted) setState(() {});
      }
    }
    if (state.state == CallStateEnum.ENDED || state.state == CallStateEnum.FAILED) {
      _close();
    }
  }

  @override void registrationStateChanged(RegistrationState state) {}
  @override void transportStateChanged(TransportState state) {}
  @override void onNewMessage(SIPMessageRequest msg) {}
  @override void onNewNotify(Notify ntf) {}
  @override void onNewReinvite(ReInvite event) {}

  @override
  void dispose() {
    try { SipService.instance.helper.removeSipUaHelperListener(this); } catch (_) {}
    try { _remote.dispose(); } catch (_) {}
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Stack(children: [
          Positioned.fill(
            child: (_ready && _remote.srcObject != null)
                ? RTCVideoView(_remote, objectFit: RTCVideoViewObjectFit.RTCVideoViewObjectFitCover)
                : Container(
                    color: const Color(0xFF10151A),
                    child: Center(
                      child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                        const Icon(Icons.doorbell, color: Colors.white54, size: 56),
                        const SizedBox(height: 14),
                        Text(widget.caller,
                            style: const TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.w600)),
                        const SizedBox(height: 6),
                        const Text('Kapı arıyor…', style: TextStyle(color: Colors.white54)),
                      ]),
                    ),
                  ),
          ),
          Positioned(
            top: 14, left: 0, right: 0,
            child: Center(
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(14)),
                child: Text(widget.caller,
                    style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
              ),
            ),
          ),
          Positioned(
            bottom: 36, left: 0, right: 0,
            child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              if (!_answered)
                _RoundBtn(color: const Color(0xFF1FA85C), icon: Icons.call, onTap: _answer),
              if (_answered) ...[
                _RoundBtn(color: const Color(0xFF1FA85C), icon: Icons.lock_open, onTap: _openDoor),
                const SizedBox(width: 26),
                _RoundBtn(color: Colors.red, icon: Icons.call_end, onTap: _hangup),
              ] else ...[
                const SizedBox(width: 26),
                _RoundBtn(color: Colors.red, icon: Icons.call_end, onTap: _reject),
              ],
            ]),
          ),
        ]),
      ),
    );
  }
}

class _RoundBtn extends StatelessWidget {
  final Color color; final IconData icon; final VoidCallback onTap;
  const _RoundBtn({required this.color, required this.icon, required this.onTap});
  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: onTap,
        child: Container(
          width: 66, height: 66,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          child: Icon(icon, color: Colors.white, size: 30),
        ),
      );
}
