package com.mobildiafon.box

/**
 * DiafonBox'ın aradığı daire. box-activate cevabından gelir.
 *  - apartmentId : call:start-flat'e gönderilen kimlik
 *  - flatNo      : ekranda gösterilecek daire no ("20")
 *  - name        : varsa etiket/sakin adı (opsiyonel)
 */
data class Apartment(
    val apartmentId: String,
    val flatNo: String,
    val name: String = ""
)
