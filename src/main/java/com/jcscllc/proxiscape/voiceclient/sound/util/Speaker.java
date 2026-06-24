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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Speaker extends Thread {

    private final SoundManager soundManager;

    @Setter
    @Getter
    private SourceDataLine spkr;

    @Getter
    private Mixer.Info spkrInfo;

    @Getter
    private boolean running;

    public Speaker(SoundManager soundManager) {
        this.soundManager = soundManager;

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

    public void end() {
        running = false;

        if (spkr != null)
            spkr.close();
    }

    private Map<String, JitterBuffer> jitterBufferMap = new ConcurrentHashMap<String, JitterBuffer>();
    private Map<String, OpusDecoder> decoders = new ConcurrentHashMap<String, OpusDecoder>();

    private class JitterBuffer {
        private final Map<Integer, Object[]> buffer = new ConcurrentHashMap<Integer, Object[]>();

        private int expectedSeq = -1;
        private long lastPacketTime;

        // 40ms = good default for internet VoIP
        private static final long JITTER_DELAY_MS = 40;

        public void add(int sequence, Object[] p)
        {
            if (sequence < expectedSeq)
                return; // too late, drop

            buffer.put(sequence, p);

            if (expectedSeq == -1)
                expectedSeq = sequence;
        }

        public Object[] poll() {
            Object[] next = buffer.get(expectedSeq);

            long now = System.currentTimeMillis();

            // If packet arrived → play it
            if (next != null)
            {
                buffer.remove(expectedSeq);
                expectedSeq = (expectedSeq + 1) & 0xFFFF;
                lastPacketTime = now;
                return next;
            }

            // If missing too long → use PLC (skip)
            if (now - lastPacketTime > JITTER_DELAY_MS)
            {
                lastPacketTime = now;
                expectedSeq = (expectedSeq + 1) & 0xFFFF;
                return null; // triggers Opus concealment
            }

            // Wait a bit more for late packet
            return null;
        }
    }

    public void queueStaticSoundPacket(int packetSequence, byte[] soundPacket) {
        queueLocationalSoundPacket(packetSequence, soundPacket, (String) ProxiScapePlugin.PLAYER_INFO[1], (int) ProxiScapePlugin.PLAYER_INFO[2], (int) ProxiScapePlugin.PLAYER_INFO[3], (int) ProxiScapePlugin.PLAYER_INFO[4], (int) ProxiScapePlugin.PLAYER_INFO[5]);
    }

    public void queueLocationalSoundPacket(int packetSequence, byte[] soundPacket, String name, int world, int x, int y, int plane) {
        if (spkr != null) {
            if (jitterBufferMap.containsKey(name)) {

                jitterBufferMap.get(name).add(packetSequence, new Object[]{soundPacket, name, world, x, y, plane});
                return;
            }

            JitterBuffer jb = new JitterBuffer();

            jb.add(packetSequence, new Object[]{soundPacket, name, world, x, y, plane});

            jitterBufferMap.put(name, jb);

            try {
                decoders.put(name, new OpusDecoder(16000, 1));
            } catch (OpusException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void run() {
        try {
            spkr.open(soundManager.getAudioFormat());
            spkr.start();

            int frameSize = 960;

            short[] mix = new short[frameSize];
            short[] pcmOut = new short[frameSize];

            ProxiScapePlugin plugin = soundManager.getVoiceClient().getPlugin();

            while (running) {

                long frameStart = System.currentTimeMillis();

                Arrays.fill(mix, (short) 0);

                for (Map.Entry<String, JitterBuffer> entry : jitterBufferMap.entrySet()) {
                    String user = entry.getKey();

                    if (plugin.getConfig().deafened())
                        continue;

                    if (plugin.isPublicChatOff())
                        continue;

                    if (plugin.isPublicChatFriendsOnly() && !plugin.isPlayerFriend(user))
                        continue;

                    if (plugin.isPlayerIgnored(user))
                        continue;

                    JitterBuffer jb = entry.getValue();
                    OpusDecoder decoder = decoders.get(user);

                    if (decoder == null)
                        continue;

                    Object[] soundPacket = jb.poll();

                    // --- decode (packet OR PLC) ---
                    if (soundPacket == null)
                        decoder.decode(null, 0, 0, pcmOut, 0, frameSize, false);
                    else {
                        byte[] opusData = (byte[]) soundPacket[0];

                        decoder.decode(
                                opusData, 0, opusData.length,
                                pcmOut, 0, frameSize,
                                false
                        );
                    }
                    
                    // --- distance attenuation ---
                    float gain = 1.0f;

                    if (soundPacket != null) {
                        int x1 = (int) ProxiScapePlugin.PLAYER_INFO[3];
                        int y1 = (int) ProxiScapePlugin.PLAYER_INFO[4];

                        int x2 = (int) soundPacket[3];
                        int y2 = (int) soundPacket[4];

                        int dx = x2 - x1;
                        int dy = y2 - y1;

                        float distance = (float) Math.sqrt(dx * dx + dy * dy);

                        gain = 1.0f - (distance / 20.0f);

                        if (gain < 0f)
                            gain = 0f;
                    }

                    // --- mix into global buffer ---
                    for (int i = 0; i < frameSize; i++) {
                        int sample = (int) (pcmOut[i] * gain) + mix[i];

                        if (sample > Short.MAX_VALUE) sample = Short.MAX_VALUE;
                        if (sample < Short.MIN_VALUE) sample = Short.MIN_VALUE;

                        mix[i] = (short) sample;
                    }

                    if (soundPacket != null) {
                        soundManager.getVoiceClient()
                                .getPlugin()
                                .getOverlay()
                                .spoke(user);
                    }
                }

                // --- convert mix → bytes ---
                byte[] output = new byte[frameSize * 2];

                for (int i = 0; i < frameSize; i++) {
                    output[i * 2] = (byte) (mix[i] & 0xff);
                    output[i * 2 + 1] = (byte) ((mix[i] >> 8) & 0xff);
                }

                spkr.write(output, 0, output.length);

                long frameTime = System.currentTimeMillis() - frameStart;
                long sleep = 20 - frameTime;

                if (sleep > 0)
                {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ignored) {}
                }
            }

        } catch (OpusException | LineUnavailableException e) {
            e.printStackTrace();
        }
    }

}
