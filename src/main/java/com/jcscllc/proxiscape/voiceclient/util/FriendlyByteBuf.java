package com.jcscllc.proxiscape.voiceclient.util;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Getter
public class FriendlyByteBuf {

    private final ByteBuffer buffer;

    public FriendlyByteBuf(int capacity) {
        this.buffer = ByteBuffer.allocate(capacity);
        this.buffer.order(ByteOrder.BIG_ENDIAN);
    }

    public FriendlyByteBuf(ByteBuffer buffer) {
        this.buffer = buffer;
        this.buffer.order(ByteOrder.BIG_ENDIAN);
    }

    public static FriendlyByteBuf wrap(byte[] data) {
        return new FriendlyByteBuf(ByteBuffer.wrap(data));
    }

    // ===== WRITE =====

    public FriendlyByteBuf writeByte(int b) {
        buffer.put((byte) b);
        return this;
    }

    public FriendlyByteBuf writeBytes(byte[] bytes) {
        buffer.put(bytes);
        return this;
    }

    public FriendlyByteBuf writeBytes(byte[] bytes, int length) {
        for (int i = 0; i < length; i++)
            buffer.put(bytes[i]);
        return this;
    }

    public FriendlyByteBuf writeInt(int i) {
        buffer.putInt(i);
        return this;
    }

    public FriendlyByteBuf writeShort(short s) {
        buffer.putShort(s);
        return this;
    }

    public FriendlyByteBuf writeLong(long l) {
        buffer.putLong(l);
        return this;
    }

    public FriendlyByteBuf writeUUID(UUID uuid) {
        writeLong(uuid.getMostSignificantBits());
        writeLong(uuid.getLeastSignificantBits());
        return this;
    }

    public FriendlyByteBuf writeByteArray(byte[] bs) {
        writeVarInt(bs.length);
        buffer.put(bs);
        return this;
    }

    public FriendlyByteBuf writeUtf(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        buffer.put(bytes);
        return this;
    }

    public FriendlyByteBuf writeVarInt(int value) {
        while ((value & -128) != 0) {
            buffer.put((byte) (value & 127 | 128));
            value >>>= 7;
        }
        buffer.put((byte) value);
        return this;
    }

    // ===== READ =====

    public byte readByte() {
        return buffer.get();
    }

    public int readInt() {
        return buffer.getInt();
    }

    public long readLong() {
        return buffer.getLong();
    }

    public short readShort() {return buffer.getShort(); }

    public UUID readUUID() {
        return new UUID(readLong(), readLong());
    }

    public byte[] readBytes(int length) {
        byte[] out = new byte[length];
        buffer.get(out);
        return out;
    }

    public byte[] readByteArray() {
        int len = readVarInt();
        return readBytes(len);
    }

    public String readUtf() {
        int len = readVarInt();
        byte[] bytes = readBytes(len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public int readVarInt() {
        int numRead = 0;
        int result = 0;
        byte read;

        do {
            read = buffer.get();
            int value = (read & 0b01111111);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 5) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((read & 0b10000000) != 0);

        return result;
    }

    // ===== BUFFER CONTROL =====

    public int readableBytes() {
        return buffer.remaining();
    }

    public void flip() {
        buffer.flip(); // switch write -> read
    }

    public void clear() {
        buffer.clear();
    }

    public byte[] toByteArray() {
        int pos = buffer.position();
        byte[] out = new byte[pos];
        buffer.rewind();
        buffer.get(out);
        return out;
    }
}

