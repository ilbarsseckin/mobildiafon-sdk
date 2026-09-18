package com.mobildiafon.rtc;

import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * MobilDiafon SDK yapılandırması. Varsayılanlar mevcut çalışan sistemle birebir.
 * Host, uygulamanın başında (Application.onCreate) değiştirebilir:
 *
 *   MobilDiafonConfig c = MobilDiafon.config();
 *   c.socketUrl = "https://mobildiafon.com";
 *   c.turnUrl   = "turn:...";
 *
 * Ses robotluğu sürerse: c.inputSampleRate = 48000;
 */
public final class MobilDiafonConfig {

    /** Socket.io sunucu kök adresi. */
    public String socketUrl = "https://mobildiafon.com";
    /** REST API kök adresi (aktivasyon / guest-token). */
    public String apiBase   = "https://mobildiafon.com/api";

    /** STUN. */
    public String stunUrl  = "stun:stun.l.google.com:19302";
    /** TURN (NAT arkası için zorunlu). */
    public String turnUrl  = "turn:128.140.127.151:3478";
    public String turnUser = "diafonturn";
    public String turnPass = "turnpass2026";

    /** Fortemedia hoparlör/oynatma hızı (temiz yön). */
    public int outputSampleRate = 16000;
    /** Fortemedia mikrofon/yakalama hızı. Robotluk sürerse 48000 dene. */
    public int inputSampleRate  = 16000;

    /** Alıcı servisi socket'i, Linphone medya çakışmasını beklemek için bu kadar ms geç açar. */
    public long startDelayMs = 4000L;

    private static MobilDiafonConfig sInstance;

    public static synchronized MobilDiafonConfig get() {
        if (sInstance == null) sInstance = new MobilDiafonConfig();
        return sInstance;
    }

    List<PeerConnection.IceServer> iceServers() {
        List<PeerConnection.IceServer> l = new ArrayList<>();
        l.add(PeerConnection.IceServer.builder(stunUrl).createIceServer());
        l.add(PeerConnection.IceServer.builder(turnUrl)
                .setUsername(turnUser).setPassword(turnPass).createIceServer());
        return l;
    }
}
