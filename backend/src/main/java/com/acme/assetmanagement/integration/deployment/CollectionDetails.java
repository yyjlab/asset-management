package com.acme.assetmanagement.integration.deployment;

import java.util.*;
import static com.acme.assetmanagement.integration.deployment.DeploymentRegistrationRequest.*;

public record CollectionDetails(String platform, String computerType, String status, List<Monitor> monitors, List<String> mouseBrands, List<String> keyboardBrands) {
    public record Monitor(String deviceKey, String connection, String brand, String serialNumber, Double sizeInches) {}
    public static CollectionDetails parse(Object input) {
        if(input==null)return null;
        var value=object(input,"collection");
        keys(value,"collection","platform","computerType","status","monitors","mouseBrands","keyboardBrands");
        String platform=text(value,"platform",16,true), type=text(value,"computerType",16,true), status=text(value,"status",16,true);
        if(!Set.of("windows","macos").contains(platform) || !Set.of("laptop","desktop","unknown").contains(type)
                || !Set.of("ok","partial","unavailable").contains(status)) throw validation("collection","平台、电脑类型或检测状态无效");
        var monitors=new ArrayList<Monitor>(); var identifiers=new HashSet<String>();
        for(Object raw:array(value.get("monitors"),"collection.monitors",32)) {
            var row=object(raw,"collection.monitors[]");
            keys(row,"collection.monitors[]","deviceKey","connection","brand","serialNumber","sizeInches");
            String id=text(row,"deviceKey",160,true), connection=text(row,"connection",16,true);
            if(!identifiers.add(id) || !Set.of("internal","external","unknown").contains(connection)) throw validation("collection.monitors","重复显示器标识或连接类型无效");
            Double size=null;
            if(row.get("sizeInches")!=null){
                if(!(row.get("sizeInches") instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<1 || n.doubleValue()>200)
                    throw validation("collection.monitors.sizeInches","尺寸须为1至200英寸，未知请留空");
                size=((Number)row.get("sizeInches")).doubleValue();
            }
            monitors.add(new Monitor(id,connection,text(row,"brand",80,false),text(row,"serialNumber",160,false),size));
        }
        if(status.equals("unavailable") && !monitors.isEmpty())throw validation("collection.status","读取失败时不能报告显示器列表");
        return new CollectionDetails(platform,type,status,List.copyOf(monitors),brands(value.get("mouseBrands")),brands(value.get("keyboardBrands")));
    }
    private static List<String> brands(Object input) {
        if(input==null)return List.of();
        var result=new LinkedHashSet<String>();
        for(var raw:array(input,"collection.brands",16)){
            if(!(raw instanceof String text) || text.isBlank() || text.length()>80)throw validation("collection.brands","品牌须为1至80字符");
            result.add(((String)raw).strip());
        }
        return List.copyOf(result);
    }
}
