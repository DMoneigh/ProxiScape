package com.jcscllc.proxiscape.voiceclient.packet;

import com.jcscllc.proxiscape.voiceclient.VoiceClient;
import com.jcscllc.proxiscape.voiceclient.util.FriendlyByteBuf;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class PacketManager {

    private static final String VOICE_SERVER_IP = "18.212.139.228";

    private static final int VOICE_SERVER_PORT = 4545;

    private static final byte MAGIC_BYTE = (byte) 0b11111111;

    private static final long PACKET_TTL = 10_000L;

    private static final byte AUTHENTICATE_PACKET = 0x1;

    private static final byte AUTHENTICATE_ACK_PACKET = 0x2;

    private static final byte KEEP_ALIVE_PACKET = 0x3;

    private static final byte LOCATIONAL_VOICE_PACKET = 0x4;

    private static final byte STATIC_VOICE_PACKET = 0x5;

    private static final byte UPDATE_POSITION_PACKET = 0x6;

    private static final byte PING_PACKET = 0x7;

    private static final byte PONG_PACKET = 0x8;

    private static final byte NO_PACKET = 0x9;

    private final VoiceClient client;

    private final InetSocketAddress sock;

    private boolean running;

    public PacketManager(VoiceClient client) {
        this.client = client;

        sock = new InetSocketAddress(VOICE_SERVER_IP, VOICE_SERVER_PORT);

        running = true;
    }

    public void end() {
        running = false;
    }

    public void read(SocketAddress from, FriendlyByteBuf fbb) throws Exception {

        if (fbb.readByte() != MAGIC_BYTE) //Read magic byte
            return;

        long packetTimestamp = fbb.readLong(); //Read timestamp of packet

        if (System.currentTimeMillis() - packetTimestamp > PACKET_TTL)
            return;

        byte type = fbb.readByte(); // read packet type

        switch (type) {
            case AUTHENTICATE_ACK_PACKET:
                break;

            case LOCATIONAL_VOICE_PACKET:
                String name = fbb.readUtf();

                int world = fbb.readInt();

                int x = fbb.readInt();

                int y = fbb.readInt();

                int plane = fbb.readInt();

                int length = fbb.readVarInt();

                byte[] opusIn = fbb.readBytes(length);

                client.getSoundManager().getSpeaker().queueLocationalSoundPacket(opusIn, x, y, plane);
                break;

            case STATIC_VOICE_PACKET:
                length = fbb.readVarInt();

                opusIn = fbb.readBytes(length);

                client.getSoundManager().getSpeaker().queueStaticSoundPacket(opusIn);
                break;

            case PONG_PACKET:
                //TODO Show user is connected..
                break;

            case NO_PACKET:
                //TODO Say user is not connected
                break;

            default:
                break;
        }
    }

    public void write(byte type, SocketAddress to, FriendlyByteBuf data) throws Exception {
        FriendlyByteBuf fbb = new FriendlyByteBuf(5000);
        fbb.writeByte(MAGIC_BYTE); //Write magic byte
        fbb.writeLong(System.currentTimeMillis()); //Write timestamp
        fbb.writeByte(type); //Write type

        byte[] array = data.toByteArray();

        fbb.writeBytes(array, array.length); //Append data

        byte[] writable = fbb.toByteArray();
        client.getSocket().send(new DatagramPacket(writable, writable.length, to));
    }

    public void writeAuthenticatePacket(String hash, String username) throws Exception {
        FriendlyByteBuf buf = new FriendlyByteBuf(5000);
        buf.writeUtf(hash);
        buf.writeUtf(username);

        write(AUTHENTICATE_PACKET, sock, buf);
    }

    public void writeKeepAlivePacket(String hash) throws Exception {
        FriendlyByteBuf buf = new FriendlyByteBuf(5000);
        buf.writeUtf(hash);

        write(KEEP_ALIVE_PACKET, sock, buf);
    }

    public void writePositionUpdatePacket(String hash, int world, int x, int y) throws Exception {
        FriendlyByteBuf buf = new FriendlyByteBuf(5000);
        buf.writeUtf(hash);
        buf.writeInt(world);
        buf.writeInt(x);
        buf.writeInt(y);

        write(UPDATE_POSITION_PACKET, sock, buf);
    }

    public void writeLocationalVoiceData(String hash, int world, int x, int y, int plane, byte[] voiceData, int length) throws Exception {
        FriendlyByteBuf buf = new FriendlyByteBuf(5000);
        buf.writeUtf(hash);
        buf.writeInt(world);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(plane);
        buf.writeVarInt(length);
        buf.writeBytes(voiceData, length);

        write(LOCATIONAL_VOICE_PACKET, sock, buf);
    }

}
