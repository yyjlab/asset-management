package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.lookup.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class AccessoryStockService {
    private final AccessoryStockRepository stocks;
    private final AssetRepository assets;
    private final LookupRepository lookups;
    private final AuditLogService audit;
    private final AuditLogRepository logs;
    public AccessoryStockService(AccessoryStockRepository stocks, AssetRepository assets, LookupRepository lookups, AuditLogService audit, AuditLogRepository logs) {
        this.stocks=stocks;this.assets=assets;this.lookups=lookups;this.audit=audit;this.logs=logs;
    }
    public record Row(Long id,long version,String type,String brand,Long companyId,String company,Long locationId,String location,int total,long available,long paired,long used,int scrapped) {}
    public record Allocation(Long assetId,String assetTag,String name,String assignedTo,int quantity,boolean checkedOut) {}
    public record Event(java.time.LocalDateTime time,String operator,String summary,String changes) {}
    public record Details(List<Allocation> allocations,List<Event> events) {}
    @Transactional(readOnly=true)
    public List<Row> list(Long companyId,Long locationId) {
        return stocks.findAll().stream().filter(s->(companyId==null||s.companyId.equals(companyId))&&(locationId==null||s.locationId.equals(locationId))).map(this::row).toList();
    }
    private Row row(AccessoryStock s) {
        s=org.hibernate.Hibernate.unproxy(s,AccessoryStock.class);
        long used=assets.countStockQuantity(s.id,true),paired=assets.countStockQuantity(s.id,false);
        return new Row(s.id,s.version,s.type,s.brand,s.companyId,lookups.findById(s.companyId).map(LookupValue::getName).orElse("已移除公司"),s.locationId,lookups.findById(s.locationId).map(LookupValue::getName).orElse("已移除位置"),s.total,s.total-s.scrapped-used-paired,paired,used,s.scrapped);
    }
    public Row create(String type,String brand,Long companyId,Long locationId,int quantity) {
        type=type.trim();brand=brand.trim();if(type.isEmpty()||brand.isEmpty())throw bad("类型和品牌不能为空");
        requireLookup(companyId,LookupType.COMPANY);requireLookup(locationId,LookupType.LOCATION);
        String identity;
        try { identity=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((type.toLowerCase(Locale.ROOT)+"\0"+brand.toLowerCase(Locale.ROOT)+"\0"+companyId+"\0"+locationId).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        if(stocks.existsByIdentityKey(identity))throw conflict("同类型、品牌、公司和位置的库存已存在，请选择补货");
        var s=new AccessoryStock();s.type=type;s.brand=brand;s.companyId=companyId;s.locationId=locationId;s.identityKey=identity;s.total=quantity;
        try { stocks.saveAndFlush(s); }
        catch(org.springframework.dao.DataIntegrityViolationException e){throw conflict("相同品牌库存已存在或公司、位置已变化，请刷新后核对");}
        record(s,"配件入库",Map.of("数量",quantity));return row(s);
    }
    public Row adjust(Long id,long version,int quantity,boolean scrap,String note) {
        var s=locked(id);
        if(s.version!=version)throw conflict("库存已更新，请刷新后重试，勿重复入库");
        if(scrap){if(row(s).available()<quantity)throw conflict("可用库存不足；使用中的配件需先确认拆下退库");s.scrapped=Math.addExact(s.scrapped,quantity);}
        else {if((long)s.total+quantity>9999999)throw bad("总数量不能超过9999999");s.total+=quantity;}
        stocks.saveAndFlush(s);record(s,scrap?"配件报废":"配件补货",Map.of("数量",quantity,"备注",note==null?"":note.trim()));return row(s);
    }
    private void requireLookup(Long id,LookupType type){if(id==null||lookups.findById(id).filter(l->l.getType()==type&&!l.isDisabled()).isEmpty())throw bad("请选择启用的"+type.getLabel());}
    // Lock stock rows in stable order. Every asset allocation change uses this same transaction.
    public void reconcile(Asset before,List<AssetRequest.RelatedDeviceRequest> requested,Long companyId,Long locationId,boolean confirmReturn) {
        Map<Long,Integer> old=new TreeMap<>(),next=new TreeMap<>();
        if(before!=null)for(var d:before.getRelatedDevices())if(d.getStockId()!=null)old.merge(d.getStockId(),d.getQuantity(),Integer::sum);
        for(var d:requested==null?List.<AssetRequest.RelatedDeviceRequest>of():requested)if(d.stockId()!=null){
            if(d.stockId()<=0||d.quantity()<1||d.quantity()>9999||next.putIfAbsent(d.stockId(),d.quantity())!=null)throw bad("同一库存配件不能重复选择，数量须为1至9999");
        }
        var ids=new TreeSet<>(old.keySet());ids.addAll(next.keySet());
        for(Long id:ids){
            var s=locked(id);int delta=next.getOrDefault(id,0)-old.getOrDefault(id,0);
            if(next.containsKey(id)){
                if(!s.companyId.equals(companyId)||!s.locationId.equals(locationId))throw conflict("配件库存的公司或位置与电脑不一致，请先退库后重新选择");
                var d=requested.stream().filter(r->id.equals(r.stockId())).findFirst().orElseThrow();
                if(!s.type.equals(d.name().trim())||!s.brand.equals(d.model()==null?"":d.model().trim()))throw bad("配件类型或品牌与库存不符，请重新选择");
            }
            if(delta>row(s).available())throw conflict(s.type+" / "+s.brand+" 库存不足，请刷新后重新选择");
            if(delta<0&&!confirmReturn)throw conflict("减少或移除库存配件前，请确认已实际拆下并退库");
            if(delta!=0)record(s,delta>0?"分配随附配件":"配件退库",Map.of("数量",Math.abs(delta),"资产",before==null?"新建资产":before.getAssetTag()));
        }
    }
    @Transactional(readOnly=true)
    public Details details(Long id){
        if(!stocks.existsById(id))throw bad("配件库存不存在");
        var allocations=assets.stockAllocations(id).stream().map(a->new Allocation((Long)a[0],(String)a[1],(String)a[2],(String)a[3],((Number)a[4]).intValue(),(Boolean)a[5])).toList();
        var events=logs.findFirst100ByTargetTypeAndTargetIdOrderByOccurredAtDesc("ACCESSORY_STOCK",id).stream().map(l->new Event(l.getOccurredAt(),l.getActorUsername(),l.getSummary(),l.getChangesJson())).toList();
        return new Details(allocations,events);
    }
    private void record(AccessoryStock s,String summary,Map<String,?> changes){audit.success(AuditAction.ASSET_UPDATE,"ACCESSORY_STOCK",s.id,s.type+" / "+s.brand,summary,new LinkedHashMap<>(changes));}
    private AccessoryStock locked(Long id){return org.hibernate.Hibernate.unproxy(stocks.locked(id).orElseThrow(()->bad("配件库存不存在")),AccessoryStock.class);}
    private ApiException bad(String text){return new ApiException(HttpStatus.BAD_REQUEST,text);}
    private ApiException conflict(String text){return new ApiException(HttpStatus.CONFLICT,text);}
}
