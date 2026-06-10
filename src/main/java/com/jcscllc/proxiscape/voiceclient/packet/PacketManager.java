package com.jcscllc.proxiscape.voiceclient.packet;

import com.jcscllc.proxiscape.voiceclient.VoiceClient;
import com.jcscllc.proxiscape.voiceclient.util.FriendlyByteBuf;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class PacketManager {

    private static final int MAXIMUM_PACKET_SIZE = 4000;

    private static final String VOICE_SERVER_DOMAIN_USE = "proxiscape-use.jcsc-llc.com";

    private static final String VOICE_SERVER_DOMAIN_UK = "proxiscape-uk.jcsc-llc.com";

    private static final String VOICE_SERVER_DOMAIN_DE = "proxiscape-de.jcsc-llc.com";

    private static final String VOICE_SERVER_DOMAIN_AUS = "proxiscape-aus.jcsc-llc.com";

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

    private final VoiceClient voiceClient;

    private InetSocketAddress sock;

    private boolean running;

    public PacketManager(VoiceClient voiceClient) {
        this.voiceClient = voiceClient;

        running = true;
    }

    public void createVoiceSocket(int worldType) {
        String voiceServer = VOICE_SERVER_DOMAIN_USE;

        switch (worldType) {
            case 1 :
                voiceServer = VOICE_SERVER_DOMAIN_UK;
                break;

            case 3 :
            case 10 :
            case 12 :
                voiceServer = VOICE_SERVER_DOMAIN_AUS;
                break;

            case 7 :
                voiceServer = VOICE_SERVER_DOMAIN_DE;
                break;

            default :
                voiceServer = VOICE_SERVER_DOMAIN_USE;
                break;
        }

        sock = new InetSocketAddress(voiceServer, VOICE_SERVER_PORT);
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

                voiceClient.getSoundManager().getSpeaker().queueLocationalSoundPacket(opusIn, name, world, x, y, plane);
                break;

            case STATIC_VOICE_PACKET:
                length = fbb.readVarInt();

                opusIn = fbb.readBytes(length);

                voiceClient.getSoundManager().getSpeaker().queueStaticSoundPacket(opusIn);
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
        FriendlyByteBuf fbb = new FriendlyByteBuf(MAXIMUM_PACKET_SIZE);
        fbb.writeByte(MAGIC_BYTE); //Write magic byte
        fbb.writeLong(System.currentTimeMillis()); //Write timestamp
        fbb.writeByte(type); //Write type

        byte[] array = data.toByteArray();

        fbb.writeBytes(array, array.length); //Append data

        byte[] writable = fbb.toByteArray();

        voiceClient.getSocket().send(new DatagramPacket(writable, writable.length, to));
    }

    public void writeAuthenticatePacket(String hash, String username) throws Exception {
        FriendlyByteBuf buf = new FriendlyByteBuf(MAXIMUM_PACKET_SIZE);
        buf.writeUtf(hash);
        buf.writeUtf(username);

        write(AUTHENTICATE_PACKET, sock, buf);
    }

    public void writeKeepAlivePacket(String hash) throws Exception {
        FriendlyByteBuf buf = new FriendlyByteBuf(MAXIMUM_PACKET_SIZE);
        buf.writeUtf(hash);

        write(KEEP_ALIVE_PACKET, sock, buf);
    }

    public void writePingPacket(String hash) throws Exception {
        FriendlyByteBuf buf = new FriendlyByteBuf(MAXIMUM_PACKET_SIZE);
        buf.writeUtf(hash);

        write(PING_PACKET, sock, buf);
    }

    public void writeUpdatePositionPacket(String hash, int world, int x, int y) throws Exception {
        FriendlyByteBuf buf = new FriendlyByteBuf(MAXIMUM_PACKET_SIZE);
        buf.writeUtf(hash);
        buf.writeInt(world);
        buf.writeInt(x);
        buf.writeInt(y);

        write(UPDATE_POSITION_PACKET, sock, buf);
    }

    public void writeLocationalVoiceData(String hash, int world, int x, int y, int plane, byte[] voiceData, int length) throws Exception {
        FriendlyByteBuf buf = new FriendlyByteBuf(MAXIMUM_PACKET_SIZE);
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
