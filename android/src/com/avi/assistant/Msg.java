package com.avi.assistant;

/** Satu gelembung riwayat percakapan. */
public final class Msg {
    public final String teks;
    public final boolean dariUser;

    public Msg(String teks, boolean dariUser) {
        this.teks = teks == null ? "" : teks;
        this.dariUser = dariUser;
    }
}
