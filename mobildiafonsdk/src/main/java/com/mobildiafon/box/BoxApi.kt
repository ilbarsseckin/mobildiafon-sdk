package com.mobildiafon.box

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * DiafonBox HTTP + önbellek.
 *   - box-activate: MAC -> buildingQrToken + daireler   (auth YOK — MAC kimliktir)
 *   - guest-token : qrToken -> geçici GUEST jwt          (socket için)
 * Aktivasyon sonucu SharedPreferences'ta saklanır; kutu yeniden açılınca
 * ağ beklemeden daire listesini gösterebilir.
 */
internal class BoxApi(ctx: Context, private val cfg: BoxConfig) {

    companion object {
        private const val TAG = "DiafonBoxApi"
        private const val PREFS = "diafonbox"
        private const val K_QR = "box_qr"
        private const val K_BID = "box_building_id"
        private const val K_BNAME = "box_building_name"
        private const val K_APTS = "box_apartments"
        private const val K_ACTIVE = "box_active"
        private const val K_BLOCK = "box_block"    // kutunun blogu (1..19)
        private const val K_DOOR = "box_door"      // kutunun kapisi (1..4)
        private const val K_TYPE = "box_type"      // marka/protokol: Multitek=1, Audio/Netelsan=2
    }

    private val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- önbellek erişimi ----
    val buildingQrToken: String get() = prefs.getString(K_QR, "") ?: ""
    val buildingId: String get() = prefs.getString(K_BID, "") ?: ""
    val buildingName: String get() = prefs.getString(K_BNAME, "") ?: ""
    val isActive: Boolean get() = prefs.getBoolean(K_ACTIVE, false) && buildingQrToken.isNotEmpty()
    val block: Int get() = prefs.getInt(K_BLOCK, 0)   // callToAnalogDoor icin
    val door: Int get() = prefs.getInt(K_DOOR, 0)
    val boxType: Int get() = prefs.getInt(K_TYPE, 0)  // 1=Multitek, 2=Audio/Netelsan

    fun apartments(): List<Apartment> {
        val out = ArrayList<Apartment>()
        try {
            val arr = JSONArray(prefs.getString(K_APTS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Apartment(
                        o.optString("apartmentId"),
                        o.optString("flatNo"),
                        o.optString("name", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "apartments parse", e)
        }
        return out
    }

    fun clear() {
        prefs.edit().remove(K_QR).remove(K_BID).remove(K_BNAME).remove(K_APTS)
            .remove(K_BLOCK).remove(K_DOOR).remove(K_TYPE).putBoolean(K_ACTIVE, false).apply()
    }

    /**
     * POST /calls/box-activate { deviceId: mac }  (senkron — arka thread'den çağır).
     * Başarılıysa önbelleğe yazar. Dönen: (ok, message).
     */
    fun activateSync(mac: String): Pair<Boolean, String> {
        return try {
            val res = postJson(cfg.apiBase + "/calls/box-activate", JSONObject().put("deviceId", mac), null)
            val qr = res.optString("buildingQrToken", "")
            if (res.optBoolean("success", false) && qr.isNotEmpty()) {
                val aps = res.optJSONArray("apartments")
                prefs.edit()
                    .putString(K_QR, qr)
                    .putString(K_BID, res.optString("buildingId", ""))
                    .putString(K_BNAME, res.optString("buildingName", ""))
                    .putString(K_APTS, aps?.toString() ?: "[]")
                    .putInt(K_BLOCK, res.optInt("block", 0))
                    .putInt(K_DOOR, res.optInt("door", 0))
                    .putInt(K_TYPE, res.optInt("box_type", 0))
                    .putBoolean(K_ACTIVE, true)
                    .apply()
                true to res.optString("buildingName", "Kutu aktive edildi")
            } else {
                false to res.optString("message", "Kutu aktivasyonu basarisiz (MAC panele eklendi mi?)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "activate", e)
            false to ("Aktivasyon hatasi: " + e.message)
        }
    }

    /** POST /auth/guest-token { qrToken } -> token (senkron — arka thread'den çağır). */
    fun guestTokenSync(): String {
        val qr = buildingQrToken
        if (qr.isEmpty()) throw Exception("kutu aktif degil (buildingQrToken yok)")
        val res = postJson(cfg.apiBase + "/auth/guest-token", JSONObject().put("qrToken", qr), null)
        val t = res.optString("token", "")
        if (t.isEmpty()) throw Exception(res.optString("message", "guest-token alinamadi"))
        return t
    }

    // ---- düşük seviye ----
    private fun postJson(urlStr: String, body: JSONObject, bearer: String?): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (!bearer.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val sb = StringBuilder()
            stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { r ->
                    var line = r.readLine()
                    while (line != null) { sb.append(line); line = r.readLine() }
                }
            }
            return if (sb.isEmpty()) JSONObject() else JSONObject(sb.toString())
        } finally {
            conn.disconnect()
        }
    }
}
