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
}
