package com.avi.assistant;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * Pabrik sesi asisten — Android memanggil ini setiap kali pemilik
 * memanggil AVI lewat tahan tombol home / sapu sudut bawah.
 */
public class AviSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new AviSession(AviBrain.terapkanTema(this));
    }
}
