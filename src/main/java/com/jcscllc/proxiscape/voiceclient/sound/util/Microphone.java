package com.jcscllc.proxiscape.voiceclient.sound.util;

import com.jcscllc.proxiscape.ProxiScapePlugin;
import com.jcscllc.proxiscape.voiceclient.sound.SoundManager;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import lombok.Getter;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.Map;

public class Microphone extends Thread {

    private final SoundManager soundManager;

    private TargetDataLine mic;

    @Getter
    private boolean running;

    @Getter
    private boolean muted;

    public Microphone(SoundManager soundManager) {
        this.soundManager = soundManager;

        setDaemon(true);
    }

    public void captureAndStartMicrophone() {
        JDialog dialog = new JDialog();

        dialog.setTitle("Choose your Microphone");
        dialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        dialog.setLocationRelativeTo(null);
        dialog.setSize(400, 150);
        dialog.setAlwaysOnTop(true);

        Microphone clazz = this;
        Map<String, Mixer.Info> mics = getMicrophoneMap();
        JComboBox<String> micNames = new JComboBox<String>(mics.keySet().toArray(new String[mics.size()]));

        JButton selectB = new JButton("Select Microphone");

        selectB.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {

                DataLine.Info info = new DataLine.Info(TargetDataLine.class, SoundManager.AUDIO_FORMAT);
                Mixer mixer = AudioSystem.getMixer(mics.get(micNames.getSelectedItem().toString()));

                try {
                    mic = (TargetDataLine) mixer.getLine(info);

                    running = true;

                    clazz.start();

                    dialog.dispose();
                } catch (LineUnavailableException ex) {
                    throw new RuntimeException(ex);
                }
            }
        });

        dialog.add(micNames, BorderLayout.CENTER);
        dialog.add(selectB, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    private Map<String, Mixer.Info> getMicrophoneMap() {
        Map<String, Mixer.Info> microphones = new HashMap<String, Mixer.Info>();

        Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);

            for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                if (lineInfo instanceof DataLine.Info) {
                    microphones.put(mixerInfo.toString(), mixerInfo);
                    break;
                }

            }
        }
        return microphones;
    }

    public void end() {
        running = false;

        if (mic != null)
            mic.close();
    }

    public void mute() {
        muted = true;
    }

    public void unmute() {
        muted = false;
    }

    @Override
    public void run() {
        try {
            mic.open(SoundManager.AUDIO_FORMAT);
            mic.start();

            int frameSize = 960;

            byte[] pcmBytes = new byte[frameSize * 2]; // 20ms frame (960 samples * 2 bytes)
            short[] pcmShorts = new short[frameSize];
            byte[] opusBuffer = new byte[4000]; // max packet size

            OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);

            while (running) {

                int read = mic.read(pcmBytes, 0, pcmBytes.length);
                if (read != pcmBytes.length) continue;

                for (int i = 0; i < frameSize; i++) {
                    pcmShorts[i] = (short) (
                            (pcmBytes[i * 2] & 0xff) |
                                    (pcmBytes[i * 2 + 1] << 8)
                    );
                }

                int encoded = encoder.encode(pcmShorts, 0, frameSize, opusBuffer, 0, opusBuffer.length);

                if (ProxiScapePlugin.PLAYER_INFO != null) {
                    Object[] playerInfo = ProxiScapePlugin.PLAYER_INFO;

                    if (muted)
                        continue;

                    soundManager.getClient().getPacketManager().writeLocationalVoiceData((String) playerInfo[0], (int) playerInfo[2], (int) playerInfo[3], (int) playerInfo[4], (int) playerInfo[5], opusBuffer, encoded);
//                   soundManager.getClient().getPacketManager().writeStaticVoiceData(opusBuffer, encoded);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
