package com.acme.assetmanagement.integration.deployment;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class NativeCollectionPackageTest {
    @Test void executableFooterIsLengthBoundedAndChecksummed() throws Exception {
        byte[] payload="{\"script\":\"测试中文\"}".getBytes(StandardCharsets.UTF_8);
        byte[] executable=NativeCollectionPackage.append(new byte[]{'M','Z',0,0},payload);
        assertEquals("WFQCOLL1",new String(executable,executable.length-8,8,StandardCharsets.US_ASCII));
        assertEquals(payload.length,ByteBuffer.wrap(executable,executable.length-12,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload),Arrays.copyOfRange(executable,4+payload.length,4+payload.length+32));
        assertThrows(IOException.class,()->NativeCollectionPackage.append(new byte[]{'P','K'},payload));
    }
    @Test void fixedImageSlotsRejectDuplicatesFragmentationAndOversizedContent() throws Exception {
        byte[] image="outside:SLOT            :outside".getBytes(StandardCharsets.US_ASCII);
        NativeCollectionPackage.fill(image,"SLOT",16,"中文".getBytes(StandardCharsets.UTF_8));
        assertEquals("outside:",new String(image,0,8,StandardCharsets.US_ASCII));
        assertEquals(":outside",new String(image,image.length-8,8,StandardCharsets.US_ASCII));
        assertThrows(IOException.class,()->NativeCollectionPackage.fill("SLOT    SLOT    ".getBytes(StandardCharsets.US_ASCII),"SLOT",8,new byte[1]));
        assertThrows(IOException.class,()->NativeCollectionPackage.fill("SLOTx   ".getBytes(StandardCharsets.US_ASCII),"SLOT",8,new byte[1]));
        assertThrows(IOException.class,()->NativeCollectionPackage.fill("SLOT    ".getBytes(StandardCharsets.US_ASCII),"SLOT",8,new byte[9]));
    }
    @Test void macDownloadIsAStandardUdifWithChecksumsForItsActualPayload() throws Exception {
        byte[] raw=new byte[1024];
        raw[100]=42;
        byte[] image=MacDiskImage.wrap(raw);
        var footer=ByteBuffer.wrap(image,image.length-512,512).slice();
        assertEquals(0x6b6f6c79,footer.getInt(0));
        assertEquals(4,footer.getInt(4));
        assertEquals(512,footer.getInt(8));
        assertEquals(raw.length,footer.getLong(32));
        assertEquals(raw.length,footer.getLong(216));
        assertEquals(image.length-512-raw.length,footer.getLong(224));
        assertEquals(2,footer.getLong(492));
        var crc=new java.util.zip.CRC32();crc.update(raw);
        assertEquals((int)crc.getValue(),footer.getInt(88));
        assertArrayEquals(raw,Arrays.copyOf(image,raw.length));
        raw[100]=43;
        byte[] changed=MacDiskImage.wrap(raw);
        assertNotEquals(footer.getInt(88),ByteBuffer.wrap(changed,changed.length-512,512).slice().getInt(88));
        assertThrows(IOException.class,()->MacDiskImage.wrap(new byte[0]));
        assertThrows(IOException.class,()->MacDiskImage.wrap(new byte[513]));
    }
}
