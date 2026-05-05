package com.jcscllc.proxiscape.voiceclient;

import com.jcscllc.proxiscape.ProxiScapePlugin;
import com.jcscllc.proxiscape.voiceclient.packet.PacketManager;
import com.jcscllc.proxiscape.voiceclient.sound.SoundManager;
import com.jcscllc.proxiscape.voiceclient.util.FriendlyByteBuf;
import lombok.Getter;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class VoiceClient extends Thread {

    private static final int PACKET_BUFFER_SIZE = 4096;
    private final BlockingQueue<DatagramPacket> packetQueue;
    private final PacketProcessor packetProcessor;
    @Getter
    private ProxiScapePlugin plugin;
    @Getter
    private DatagramSocket socket;

    @Getter
    private PacketManager packetManager;

    @Getter
    private SoundManager soundManager;

    @Getter
    private KeepAlive keepAlive;

    @Getter
    private boolean running;

    public VoiceClient(ProxiScapePlugin plugin) throws SocketException {
        this.plugin = plugin;

        socket = new DatagramSocket();

        running = true;

        packetQueue = new LinkedBlockingQueue<DatagramPacket>();

        packetProcessor = new PacketProcessor();

        packetManager = new PacketManager(this);

        soundManager = new SoundManager(this);

        keepAlive = new KeepAlive();

        packetProcessor.start();
    }

    @Override
    public void run() {

        while (running) {
            byte[] buffer = new byte[PACKET_BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            try {
                socket.receive(packet);

                packetQueue.add(packet);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    public void end() {
        running = false;

        keepAlive.running = false;

        packetProcessor.running = false;

        packetQueue.clear();

        packetManager.end();

        soundManager.end();

        if (socket != null)
            socket.close();
    }

    private class PacketProcessor extends Thread {

        private boolean running;

        public PacketProcessor() {
            running = true;

            setDaemon(true);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    DatagramPacket packet = packetQueue.poll(10, TimeUnit.MILLISECONDS);

                    if (packet == null)
                        continue;

                    packetManager.read(packet.getSocketAddress(), FriendlyByteBuf.wrap(packet.getData()));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public class KeepAlive extends Thread {

        private boolean running;

        public KeepAlive() {
            running = true;

            setDaemon(true);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Thread.sleep(3000);

                    if (ProxiScapePlugin.PLAYER_INFO != null)
                        packetManager.writeKeepAlivePacket(ProxiScapePlugin.PLAYER_INFO[0] + "");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
