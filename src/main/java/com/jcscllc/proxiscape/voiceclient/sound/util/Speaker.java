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

        // Last packet RECEIVED
        private long lastReceiveTime = 0;

        // Last frame (real or PLC) returned
        private long lastPlaybackTime = 0;

        private final long JITTER_DELAY_MS = 75;

        // Restart stream after 500ms of silence
        private final long STREAM_TIMEOUT_MS = 500;

        // If packet is this many frames ahead, assume a new talk spurt
        private final int MAX_REORDER = 20;

        private boolean isOlder(int seq, int expected) {
            return ((expected - seq) & 0xFFFF) < 0x8000;
        }

        public void add(int sequence, Object[] p) {
            long now = System.currentTimeMillis();

            // Long silence -> start a new stream immediately
            if (now - lastReceiveTime > STREAM_TIMEOUT_MS) {
                buffer.clear();
                expectedSeq = sequence;
            }

            lastReceiveTime = now;

            if (expectedSeq == -1)
                expectedSeq = sequence;

            // Drop packets older than what we're expecting
            if (isOlder(sequence, expectedSeq))
                return;

            // Huge gap? Probably a new talk spurt.
            int diff = (sequence - expectedSeq) & 0xFFFF;

            if (diff > MAX_REORDER) {
                buffer.clear();
                expectedSeq = sequence;
            }

            buffer.put(sequence, p);
        }

        public Object[] poll() {
            if (expectedSeq == -1)
                return null;

            long now = System.currentTimeMillis();

            // Nobody has sent anything recently.
            // Stop generating PLC forever.
            if (now - lastReceiveTime > STREAM_TIMEOUT_MS) {
                buffer.clear();
                expectedSeq = -1;
                return null;
            }

            Object[] next = buffer.remove(expectedSeq);

            if (next != null) {
                expectedSeq = (expectedSeq + 1) & 0xFFFF;
                lastPlaybackTime = now;
                return next;
            }

            // Wait a little longer for late packets.
            if (now - lastPlaybackTime >= JITTER_DELAY_MS) {
                expectedSeq = (expectedSeq + 1) & 0xFFFF;
                lastPlaybackTime = now;

                // Missing packet -> use Opus PLC
                return null;
            }

            return null;
        }

        int size() {
            return buffer.size();
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
            spkr.open(soundManager.getAudioFormat(), 960 * 2 * 4);
            spkr.start();

            int frameSize = 960;
            int STARTUP_BUFFER_FRAMES = 4; // ~80ms
            boolean started = false;

            short[] mix = new short[frameSize];
            short[] pcmOut = new short[frameSize];

            ProxiScapePlugin plugin = soundManager.getVoiceClient().getPlugin();

            while (running) {

                long frameStart = System.nanoTime();

                Arrays.fill(mix, (short) 0);

                int bufferedFrames = 0;
                for (JitterBuffer jb : jitterBufferMap.values()) {
                    bufferedFrames += jb.size(); // you need this method
                }

                if (!started && bufferedFrames < STARTUP_BUFFER_FRAMES) {
                    Thread.sleep(5);
                    continue;
                }

                started = true;

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

                    Arrays.fill(pcmOut, (short) 0);

                    if (soundPacket == null) {
                        if (!started) {
                            continue;
                        }

                        // Missing packet during an active stream.
                        decoder.decode(null, 0, 0, pcmOut, 0, frameSize, false);
                    } else {
                        byte[] opusData = (byte[]) soundPacket[0];

                        decoder.decode(opusData, 0, opusData.length, pcmOut, 0, frameSize, false);
                    }
                    
                    // --- distance attenuation ---
                    float gain = 1.0f;

                    if (soundPacket != null) {
                        int x1 = (int) ProxiScapePlugin.PLAYER_INFO[3];
                        int y1 = (int) ProxiScapePlugin.PLAYER_INFO[4];
                        int plane1 = (int) ProxiScapePlugin.PLAYER_INFO[5];

                        int x2 = (int) soundPacket[3];
                        int y2 = (int) soundPacket[4];
                        int plane2 = (int) soundPacket[5];

                        if (plane1 != plane2)
                            continue;

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

                long frameTimeNs = System.nanoTime() - frameStart;
                long sleepNs = 20_000_000 - frameTimeNs;

                if (sleepNs > 0) {
                    Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                }

            }

        } catch (OpusException | LineUnavailableException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
