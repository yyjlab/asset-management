package com.acme.assetmanagement.integration.deployment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

/** A single uncompressed UDIF extent around our bounded, trusted HFS template. */
public final class MacDiskImage {
    private MacDiskImage() {}

    public static byte[] wrap(byte[] raw) throws IOException {
        if (raw.length < 512 || raw.length % 512 != 0 || raw.length > 64 * 1024 * 1024)
            throw new IOException("Invalid collector disk template size");
        long sectors = raw.length / 512;
        int crc = crc(raw);
        // UDIF blkx: one raw run followed by a terminator; no compression or filesystem rewriting.
        ByteBuffer blocks = ByteBuffer.allocate(204 + 2 * 40);
        blocks.putInt(0x6d697368).putInt(1).putLong(0).putLong(sectors).putLong(0);
        blocks.putInt(0x208).putInt(-1);
        blocks.position(64);
        checksum(blocks, crc);
        blocks.position(200);
        blocks.putInt(2);
        blocks.putInt(1).putInt(0).putLong(0).putLong(sectors).putLong(0).putLong(raw.length);
        blocks.putInt(-1).putInt(0).putLong(sectors).putLong(0).putLong(raw.length).putLong(0);
        byte[] xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">"
                + "<plist version=\"1.0\"><dict><key>resource-fork</key><dict><key>blkx</key><array><dict>"
                + "<key>Attributes</key><string>0x0050</string><key>ID</key><string>-1</string>"
                + "<key>Name</key><string>whole disk</string><key>Data</key><data>"
                + Base64.getEncoder().encodeToString(blocks.array())
                + "</data></dict></array></dict></dict></plist>").getBytes(StandardCharsets.UTF_8);
        ByteBuffer footer = ByteBuffer.allocate(512);
        footer.putInt(0x6b6f6c79).putInt(4).putInt(512).putInt(1);
        footer.putLong(0).putLong(0).putLong(raw.length).putLong(0).putLong(0);
        footer.putInt(1).putInt(1);
        footer.position(80);
        checksum(footer, crc);
        footer.position(216);
        footer.putLong(raw.length).putLong(xml.length);
        footer.position(352);
        checksum(footer, crc(ByteBuffer.allocate(4).putInt(crc).array()));
        footer.position(488);
        footer.putInt(1).putLong(sectors);
        ByteArrayOutputStream output = new ByteArrayOutputStream(raw.length + xml.length + 512);
        output.write(raw);
        output.write(xml);
        output.write(footer.array());
        return output.toByteArray();
    }

    private static int crc(byte[] bytes) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes);
        return (int) checksum.getValue();
    }

    private static void checksum(ByteBuffer buffer, int value) {
        buffer.putInt(2).putInt(32).putInt(value);
        buffer.position(buffer.position() + 124);
    }
}
