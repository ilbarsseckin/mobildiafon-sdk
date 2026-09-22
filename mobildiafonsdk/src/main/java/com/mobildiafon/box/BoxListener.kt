package com.mobildiafon.box

/**
 * Bir kutu çağrısının durum geri bildirimleri. Tüm çağrılar ANA thread'de gelir.
 */
interface BoxListener {
    /**
     * Durum: "connecting","ringing","accepted","connected","ended:<reason>","error:<msg>"
     */
    fun onState(state: String)

    /**
     * Sakin telefondan "Kapıyı Aç" dedi. Host kapı rölesini sürsün (analog/GPIO).
     * (Aynı sinyal [DiafonBox.setRelay] ile verilen global röleye de düşer.)
     */
    fun onOpenDoor() {}
}
