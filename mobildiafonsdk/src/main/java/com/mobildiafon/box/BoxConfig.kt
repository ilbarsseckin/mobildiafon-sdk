package com.mobildiafon.box

import org.webrtc.PeerConnection

/**
 * DiafonBox yapılandırması. Varsayılanlar mobildiafon.com canlı sistemine göre ayarlı;
 * gerekirse [DiafonBox.init] ile değiştir.
 *
 * ICE sunucuları, çalışan ara.html/kapı akışıyla BİREBİR aynı (STUN + TURN).
 */
data class BoxConfig(
    val apiBase: String = "https://mobildiafon.com/api",
    val socketUrl: String = "https://mobildiafon.com",
    val stunUrl: String = "stun:stun.l.google.com:19302",
    val turnUrl: String = "turn:128.140.127.151:3478",
    val turnUser: String = "diafonturn",
    val turnPass: String = "turnpass2026"
) {
    fun iceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder(stunUrl).createIceServer(),
        PeerConnection.IceServer.builder(turnUrl)
            .setUsername(turnUser)
            .setPassword(turnPass)
            .createIceServer()
    )
}
