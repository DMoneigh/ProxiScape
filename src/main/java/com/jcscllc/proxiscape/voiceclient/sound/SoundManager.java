package com.jcscllc.proxiscape.voiceclient.sound;

import com.jcscllc.proxiscape.voiceclient.VoiceClient;
import com.jcscllc.proxiscape.voiceclient.sound.util.Microphone;
import com.jcscllc.proxiscape.voiceclient.sound.util.Speaker;
import lombok.Getter;
import lombok.Setter;

import javax.sound.sampled.AudioFormat;

public class SoundManager {

    @Getter
    private final AudioFormat audioFormat = new AudioFormat(
            16000, // sample rate
            16,    // sample size
            1,     // mono
            true,
            false
    );

    @Getter
    private VoiceClient client;

    @Setter
    @Getter
    private Microphone microphone;

    @Setter
    @Getter
    private Speaker speaker;

    public SoundManager(VoiceClient client) {
        this.client = client;

        microphone = new Microphone(this);

        speaker = new Speaker(this);
    }

    public void end() {
        microphone.end();

        speaker.end();
    }

}
