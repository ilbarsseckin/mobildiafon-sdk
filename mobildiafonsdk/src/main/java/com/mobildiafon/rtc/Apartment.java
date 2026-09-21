package com.mobildiafon.rtc;

/** DiafonBox için basit daire modeli (box-activate'ten gelir). */
public final class Apartment {
    public final String apartmentId;
    public final String flatNo;
    public final String name;   // opsiyonel sakin/daire adı
    public Apartment(String apartmentId, String flatNo, String name) {
        this.apartmentId = apartmentId;
        this.flatNo = flatNo;
        this.name = name == null ? "" : name;
    }
    @Override public String toString() { return "Daire " + flatNo; }
}
