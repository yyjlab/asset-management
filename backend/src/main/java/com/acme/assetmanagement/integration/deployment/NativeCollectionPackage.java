package com.acme.assetmanagement.integration.deployment;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Map;

@Component
public class NativeCollectionPackage {
    private final ObjectMapper json;
    public NativeCollectionPackage(ObjectMapper json){this.json=json;}
    public Map<String,Boolean> formats(){return Map.of("windows",new ClassPathResource("windows-collection/native/windows-launcher.exe").exists(),"macos",new ClassPathResource("windows-collection/native/macos-template.dmg").exists());}

    public byte[] windows(String configuration) throws IOException {
        var launcher=new ClassPathResource("windows-collection/native/windows-launcher.exe");
        var script=new ClassPathResource("windows-collection/Collect.ps1");
        try(var base=launcher.getInputStream();var source=script.getInputStream()){
            return append(base.readAllBytes(),json.writeValueAsBytes(Map.of("configuration",configuration,"script",new String(source.readAllBytes(),StandardCharsets.UTF_8))));
        }
    }
    public byte[] macos(String configuration) throws IOException {
        try(var base=new ClassPathResource("windows-collection/native/macos-template.dmg").getInputStream();var source=new ClassPathResource("windows-collection/CollectMac.js").getInputStream()){
            var bytes=base.readAllBytes();
            fill(bytes,"WFQ_COLLECTION_CONFIG_SLOT_V1",4096,configuration.getBytes(StandardCharsets.UTF_8));
            fill(bytes,"WFQ_COLLECTION_SCRIPT_SLOT_V1",131072,source.readAllBytes());
            return MacDiskImage.wrap(bytes);
        }
    }
    static void fill(byte[] image,String marker,int capacity,byte[] content) throws IOException {
        byte[] key=marker.getBytes(StandardCharsets.US_ASCII);int position=-1;
        for(int i=0;i<=image.length-key.length;i++){
            boolean match=true;for(int j=0;j<key.length;j++)if(image[i+j]!=key[j]){match=false;break;}
            if(match){if(position!=-1)throw new IOException("Repeated collector image slot");position=i;}
        }
        if(position<0||position+capacity>image.length||content.length>capacity)throw new IOException("Invalid collector image slot");
        for(int i=position+key.length;i<position+capacity;i++)if(image[i]!=' ')throw new IOException("Non-contiguous collector image slot");
        java.util.Arrays.fill(image,position,position+capacity,(byte)' ');System.arraycopy(content,0,image,position,content.length);
    }
    static byte[] append(byte[] launcher,byte[] payload) throws IOException {
        if(launcher.length<2||launcher[0]!='M'||launcher[1]!='Z'||payload.length==0||payload.length>1048576)throw new IOException("Invalid collector launcher or payload");
        var output=new ByteArrayOutputStream();output.write(launcher);output.write(payload);
        try{output.write(MessageDigest.getInstance("SHA-256").digest(payload));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array());
        output.write("WFQCOLL1".getBytes(StandardCharsets.US_ASCII));return output.toByteArray();
    }
}
