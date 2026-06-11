package com.jcscllc.proxiscape.voiceclient.sound.ui;

import javax.sound.sampled.*;
import javax.swing.*;

import com.jcscllc.proxiscape.ProxiScapePlugin;
import com.jcscllc.proxiscape.file.FileManager;
import com.jcscllc.proxiscape.voiceclient.VoiceClient;
import com.jcscllc.proxiscape.voiceclient.sound.SoundManager;
import com.jcscllc.proxiscape.voiceclient.sound.util.Microphone;
import com.jcscllc.proxiscape.voiceclient.sound.util.Speaker;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class AudioTestUI {

    private ProxiScapePlugin plugin;

    public AudioTestUI(ProxiScapePlugin plugin) {
        this.plugin = plugin;
    }

    class AudioGraphPanel extends JPanel {

        private static final long serialVersionUID = 1L;
        private static final int HISTORY_SIZE = 200;

        private final int[] levels = new int[HISTORY_SIZE];

        public synchronized void addLevel(short[] pcm) {

            long sum = 0;

            for (short s : pcm) {
                sum += (long) s * s;
            }

            double rms = Math.sqrt(sum / (double) pcm.length);

            int level = (int) Math.min(100, (rms / 32768.0) * 500);

            System.arraycopy(levels, 1, levels, 0, HISTORY_SIZE - 1);
            levels[HISTORY_SIZE - 1] = level;

            repaint();
        }

        @Override
        protected synchronized void paintComponent(Graphics g) {
            super.paintComponent(g);

            int w = getWidth();
            int h = getHeight();

            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);

            g.setColor(Color.GREEN);

            for (int i = 1; i < HISTORY_SIZE; i++) {

                int x1 = (i - 1) * w / HISTORY_SIZE;
                int x2 = i * w / HISTORY_SIZE;

                int y1 = h - (levels[i - 1] * h / 100);
                int y2 = h - (levels[i] * h / 100);

                g.drawLine(x1, y1, x2, y2);
            }
        }
    }

    static final int SAMPLE_RATE = 16000;
    static final int FRAME_SIZE = 960;
    static final int PORT = 5000;

    volatile boolean running = false;

    TargetDataLine mic;
    SourceDataLine speakers;
    DatagramSocket socket;

    OpusEncoder encoder;
    OpusDecoder decoder;

    private AudioGraphPanel outgoingGraph;
    private AudioGraphPanel incomingGraph;

    private VoiceClient client;

    public void startUI() {
       client = plugin.getVoiceClient();

        if (ProxiScapePlugin.PLAYER_INFO != null)
            client.getSoundManager().end();

        outgoingGraph = new AudioGraphPanel();
        incomingGraph = new AudioGraphPanel();

        outgoingGraph.setBorder(
                BorderFactory.createTitledBorder("Outgoing (Microphone)")
        );

        incomingGraph.setBorder(
                BorderFactory.createTitledBorder("Incoming (Received)")
        );

        JFrame frame = new JFrame("Audio Playback Test");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setLayout(new BorderLayout());
        frame.setSize(1500, 700);

        JComboBox<Mixer.Info> micDropdown = new JComboBox<>();
        JComboBox<Mixer.Info> speakerDropdown = new JComboBox<>();

        JButton startBtn = new JButton("Start");
        JButton stopBtn = new JButton("Stop");
        JButton accept = new JButton("Accept");

        // Populate devices
        List<Mixer.Info> inputs = getDevices(TargetDataLine.class);
        List<Mixer.Info> outputs = getDevices(SourceDataLine.class);

        for (Mixer.Info i : inputs) micDropdown.addItem(i);
        for (Mixer.Info o : outputs) speakerDropdown.addItem(o);

        JPanel controls = new JPanel(new FlowLayout());

        controls.add(new JLabel("Microphone:"));
        controls.add(micDropdown);

        controls.add(new JLabel("Speakers:"));
        controls.add(speakerDropdown);

        controls.add(startBtn);

        stopBtn.setEnabled(false);

        controls.add(stopBtn);

        controls.add(accept);

        JPanel graphPanel = new JPanel();
        graphPanel.setLayout(new GridLayout(2, 1));
        graphPanel.setPreferredSize(new Dimension(600, 260));

        graphPanel.add(outgoingGraph);
        graphPanel.add(incomingGraph);

        frame.add(controls, BorderLayout.NORTH);
        frame.add(graphPanel, BorderLayout.CENTER);

        startBtn.addActionListener(e -> {
            try {
                Mixer.Info micInfo = (Mixer.Info) micDropdown.getSelectedItem();
                Mixer.Info speakerInfo = (Mixer.Info) speakerDropdown.getSelectedItem();

                startVoip(micInfo, speakerInfo);

                startBtn.setEnabled(false);
                stopBtn.setEnabled(true);
                micDropdown.setEnabled(false);
                speakerDropdown.setEnabled(false);
            } catch (Exception ex) {
                ex.printStackTrace();
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                micDropdown.setEnabled(true);
                speakerDropdown.setEnabled(true);
            }
        });

        stopBtn.addActionListener(e -> {
            stopVoip();

            stopBtn.setEnabled(false);
            startBtn.setEnabled(true);
            micDropdown.setEnabled(true);
            speakerDropdown.setEnabled(true);
        });

        frame.addWindowListener(new WindowListener() {

            @Override
            public void windowOpened(WindowEvent e) {}

            @Override
            public void windowClosing(WindowEvent e) {}

            @Override
            public void windowClosed(WindowEvent e) {
                stopVoip();
            }

            @Override
            public void windowIconified(WindowEvent e) {}

            @Override
            public void windowDeiconified(WindowEvent e) {}

            @Override
            public void windowActivated(WindowEvent e) {}

            @Override
            public void windowDeactivated(WindowEvent e) {}

        });

        accept.addActionListener(e -> {
            stopVoip();

            Mixer.Info micInfo = (Mixer.Info) micDropdown.getSelectedItem();
            Mixer.Info speakerInfo = (Mixer.Info) speakerDropdown.getSelectedItem();

            if (ProxiScapePlugin.PLAYER_INFO != null) {
                SoundManager soundManager = client.getSoundManager();

                Microphone microphone = new Microphone(soundManager);
                Speaker speaker = new Speaker(soundManager);

                soundManager.setMicrophone(microphone);
                soundManager.setSpeaker(speaker);

                microphone.reconnect(micInfo);
                speaker.reconnect(speakerInfo);

                saveInfo(micInfo, speakerInfo);
            } else
                saveInfo(micInfo, speakerInfo);

            frame.dispose();
        });

        frame.setVisible(true);
    }

    void saveInfo(Mixer.Info mic, Mixer.Info spkr) {
        try {
            FileManager.writeInfo("microphone", mic);
            FileManager.writeInfo("speaker", spkr);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ===== START VOIP =====
    void startVoip(Mixer.Info micInfo, Mixer.Info speakerInfo) throws Exception {

        if (running) return;
        running = true;

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

        mic = (TargetDataLine) AudioSystem.getMixer(micInfo)
                .getLine(new DataLine.Info(TargetDataLine.class, format));

        mic.open(format);
        mic.start();

        // SPEAKERS / HEADPHONES
        speakers = (SourceDataLine) AudioSystem.getMixer(speakerInfo)
                .getLine(new DataLine.Info(SourceDataLine.class, format));

        speakers.open(format);
        speakers.start();

        socket = new DatagramSocket(PORT);

        encoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);

        decoder = new OpusDecoder(SAMPLE_RATE, 1);

        new Thread(this::sendLoop).start();
        new Thread(this::receiveLoop).start();
    }

    void stopVoip() {
        running = false;

        try {
            if (mic != null) mic.close();
            if (speakers != null) speakers.close();
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }

    }

    // ===== SEND =====
    void sendLoop() {
        try {
            byte[] pcmBytes = new byte[FRAME_SIZE * 2];
            short[] pcm = new short[FRAME_SIZE];
            byte[] opus = new byte[4000];

            short seq = 0;

            while (running) {

                mic.read(pcmBytes, 0, pcmBytes.length);

                for (int i = 0; i < FRAME_SIZE; i++) {
                    pcm[i] = (short) ((pcmBytes[i * 2] & 0xff)
                            | (pcmBytes[i * 2 + 1] << 8));
                }

                if (outgoingGraph != null) {

                    short[] copy = pcm.clone();

                    SwingUtilities.invokeLater(() ->
                            outgoingGraph.addLevel(copy)
                    );
                }


                int len = encoder.encode(pcm, 0, FRAME_SIZE, opus, 0, opus.length);

                ByteBuffer buf = ByteBuffer.allocate(2 + len);
                buf.putShort(seq++);
                buf.put(opus, 0, len);

                DatagramPacket packet = new DatagramPacket(
                        buf.array(),
                        buf.position(),
                        InetAddress.getByName("127.0.0.1"),
                        PORT
                );

                socket.send(packet);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===== RECEIVE =====
    void receiveLoop() {
        try {
            byte[] recv = new byte[1500];
            short[] pcmOut = new short[FRAME_SIZE];
            byte[] outBytes = new byte[FRAME_SIZE * 2];

            while (running) {

                DatagramPacket packet = new DatagramPacket(recv, recv.length);
                socket.receive(packet);

                ByteBuffer buf = ByteBuffer.wrap(recv, 0, packet.getLength());

                buf.getShort(); // seq

                byte[] opus = new byte[buf.remaining()];
                buf.get(opus);

                int decoded = decoder.decode(
                        opus, 0, opus.length,
                        pcmOut, 0, FRAME_SIZE,
                        false
                );

                if (incomingGraph != null) {

                    short[] copy = new short[decoded];
                    System.arraycopy(pcmOut, 0, copy, 0, decoded);

                    SwingUtilities.invokeLater(() ->
                            incomingGraph.addLevel(copy)
                    );
                }

                for (int i = 0; i < decoded; i++) {
                    outBytes[i * 2] = (byte) (pcmOut[i] & 0xff);
                    outBytes[i * 2 + 1] = (byte) (pcmOut[i] >> 8);
                }

                //                applyGain(outBytes, decoded *2, 1);
                speakers.write(outBytes, 0, decoded * 2);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===== DEVICE ENUMERATION =====
    List<Mixer.Info> getDevices(Class<?> lineClass) {

        List<Mixer.Info> list = new ArrayList<>();

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

        for (Mixer.Info info : AudioSystem.getMixerInfo()) {

            Mixer m = AudioSystem.getMixer(info);
            DataLine.Info lineInfo = new DataLine.Info(lineClass, format);

            if (m.isLineSupported(lineInfo)) {
                list.add(info);
                System.out.println(lineClass.getSimpleName() + ": " + info.getName());
            }
        }

        return list;
    }
}
