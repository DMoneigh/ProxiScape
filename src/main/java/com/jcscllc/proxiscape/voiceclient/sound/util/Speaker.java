package com.jcscllc.proxiscape.voiceclient.sound.util;

import com.jcscllc.proxiscape.ProxiScapePlugin;
import com.jcscllc.proxiscape.file.FileManager;
import com.jcscllc.proxiscape.voiceclient.sound.SoundManager;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import lombok.Getter;
import lombok.Setter;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Speaker extends Thread {

    private final SoundManager soundManager;

    private final BlockingQueue<Object[]> locationalSoundPackets;

    @Setter
    @Getter
    private SourceDataLine spkr;

    @Getter
    private Mixer.Info spkrInfo;

    @Getter
    private boolean running;

    @Getter
    private boolean deafened;

    public Speaker(SoundManager soundManager) {
        this.soundManager = soundManager;

        locationalSoundPackets = new LinkedBlockingQueue<Object[]>();

        setDaemon(true);

        running = true;
    }

    public void chooseSpeaker() {
        JDialog dialog = new JDialog();

        dialog.setTitle("Choose your Speaker");
        dialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        dialog.setLocationRelativeTo(null);
        dialog.setSize(400, 150);
        dialog.setAlwaysOnTop(true);

        Speaker clazz = this;
        Map<String, Mixer.Info> spkrs = getSpeakerMap();
        JComboBox<String> spkrNames = new JComboBox<String>(spkrs.keySet().toArray(new String[spkrs.size()]));

        JButton selectB = new JButton("Select Speaker");

        selectB.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, soundManager.getAudioFormat());
                Mixer mixer = AudioSystem.getMixer(spkrInfo = spkrs.get(spkrNames.getSelectedItem().toString()));

                storeSpeaker();

                try {
                    spkr = (SourceDataLine) mixer.getLine(info);

                    running = true;

                    clazz.start();

                    dialog.dispose();
                } catch (LineUnavailableException ex) {
                    throw new RuntimeException(ex);
                }
            }
        });

        dialog.add(spkrNames, BorderLayout.CENTER);
        dialog.add(selectB, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    private void storeSpeaker() {
        try {
            FileManager.writeInfo("speaker", spkrInfo);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Mixer.Info> getSpeakerMap() {
        Map<String, Mixer.Info> speakers = new HashMap<String, Mixer.Info>();

        Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);

            for (Line.Info lineInfo : mixer.getSourceLineInfo()) {
                if (lineInfo instanceof DataLine.Info) {
                    speakers.put(mixerInfo.toString(), mixerInfo);
                    break;
                }

            }
        }
        return speakers;
    }

    public void reconnect(Mixer.Info info) {
        DataLine.Info dInfo = new DataLine.Info(SourceDataLine.class, soundManager.getAudioFormat());

        try {
            spkr = (SourceDataLine) AudioSystem.getMixer(info).getLine(dInfo);

            running = true;

            this.start();
        } catch (LineUnavailableException e) {
            throw new RuntimeException(e);
        }
    }

    public void queueStaticSoundPacket(byte[] soundPacket) {
        queueLocationalSoundPacket(soundPacket, (String) ProxiScapePlugin.PLAYER_INFO[1], (int) ProxiScapePlugin.PLAYER_INFO[2], (int) ProxiScapePlugin.PLAYER_INFO[3], (int) ProxiScapePlugin.PLAYER_INFO[4], (int) ProxiScapePlugin.PLAYER_INFO[5]);
    }

    public void queueLocationalSoundPacket(byte[] soundPacket, String name, int world, int x, int y, int plane) {
        if (spkr != null)
            locationalSoundPackets.add(new Object[]{soundPacket, name, world, x, y, plane});
    }

    public void end() {
        running = false;

        if (spkr != null)
            spkr.close();
    }

    @Override
    public void run() {
        try {
            spkr.open(soundManager.getAudioFormat());
            spkr.start();

            int frameSize = 960;

            OpusDecoder decoder = new OpusDecoder(16000, 1);

            ProxiScapePlugin plugin = soundManager.getVoiceClient().getPlugin();

            while (running) {
                Object[] soundPacket = locationalSoundPackets.take();

                if (plugin.getConfig().deafened())
                    continue;

                if (spkr == null ||
                        ((int) ProxiScapePlugin.PLAYER_INFO[2] != (int) soundPacket[2]) ||
                        ((int) ProxiScapePlugin.PLAYER_INFO[5] != (int) soundPacket[5]) ||
                        plugin.isPlayerIgnored((String) soundPacket[1]))
                    continue;

                if (plugin.isPublicChatFriendsOnly() && !plugin.isPlayerFriend((String) soundPacket[1]))
                    continue;

                if (plugin.isPublicChatOff())
                    continue;

                byte[] opusData = (byte[]) soundPacket[0];

                short[] pcmOut = new short[frameSize];

                int decoded = decoder.decode(
                        opusData, 0, opusData.length,
                        pcmOut, 0, frameSize,
                        false
                );

                byte[] pcmBytes = new byte[frameSize * 2];

                // shorts -> bytes
                for (int i = 0; i < decoded; i++) {
                    pcmBytes[i * 2] = (byte) (pcmOut[i] & 0xff);
                    pcmBytes[i * 2 + 1] = (byte) ((pcmOut[i] >> 8) & 0xff);
                }

                int x1 = (int) ProxiScapePlugin.PLAYER_INFO[3];
                int y1 = (int) ProxiScapePlugin.PLAYER_INFO[4];

                int x2 = (int) soundPacket[3];
                int y2 = (int) soundPacket[4];

                int dx = x2 - x1;
                int dy = y2 - y1;

                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                if (distance != 0.0F)
                    applyGain(pcmBytes, decoded * 2, 1.0F - (distance / 20.0F));

                spkr.write(pcmBytes, 0, decoded * 2);

                soundManager.getVoiceClient().getPlugin().getOverlay().spoke((String) soundPacket[1]);

            }
        } catch (InterruptedException | OpusException | LineUnavailableException e) {
            e.printStackTrace();
        }

    }

    private void applyGain(byte[] buffer, int length, float gain) {
        for (int i = 0; i < length; i += 2) {
            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));

            sample = (short) (sample * gain);

            buffer[i] = (byte) (sample & 0xFF);
            buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
    }
}
