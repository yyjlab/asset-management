package com.acme.assetmanagement;

import com.acme.assetmanagement.asset.*;
import com.acme.assetmanagement.security.AccountPrincipal;
import com.acme.assetmanagement.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:accessory-stock;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc @ActiveProfiles("test")
class AccessoryStockApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired UserRepository users; @Autowired AssetRepository assets;
    private AccountPrincipal admin(){return AccountPrincipal.from(users.findByUsernameIgnoreCase("admin").orElseThrow());}
    private JsonNode stock(int quantity)throws Exception{
        var seed=assets.findAll().getFirst();
        var body=Map.of("type","鼠标","brand","品牌-"+UUID.randomUUID(),"companyId",seed.getCompany().getId(),"locationId",seed.getLocation().getId(),"quantity",quantity);
        return json.readTree(mvc.perform(post("/api/accessory-stock").with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private Map<String,Object> body(JsonNode stock,int quantity,boolean checkedOut){
        var seed=assets.findAll().getFirst();var body=new LinkedHashMap<String,Object>();
        body.put("name","配件库存测试电脑");body.put("modelId",seed.getModel().getId());body.put("companyId",stock.get("companyId").asLong());body.put("locationId",stock.get("locationId").asLong());body.put("categoryId",seed.getCategory().getId());
        var statuses=List.of("可领用","在用");
        var status=lookup(statuses.get(checkedOut?1:0));body.put("statusId",status);body.put("checkedOut",checkedOut);body.put("assignedTo",checkedOut?"测试员工":null);
        body.put("relatedDevices",List.of(Map.of("name",stock.get("type").asText(),"model",stock.get("brand").asText(),"quantity",quantity,"stockId",stock.get("id").asLong())));return body;
    }
    @Autowired com.acme.assetmanagement.lookup.LookupRepository lookups;
    private Long lookup(String name){return lookups.findByTypeAndNameIgnoreCase(com.acme.assetmanagement.lookup.LookupType.STATUS,name).orElseThrow().getId();}
    private JsonNode create(Map<String,Object> body,int status)throws Exception{return json.readTree(mvc.perform(post("/api/assets").with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsByteArray());}
    private JsonNode current(long id)throws Exception{
        var rows=json.readTree(mvc.perform(get("/api/accessory-stock").with(user(admin()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        for(var row:rows)if(row.get("id").asLong()==id)return row;throw new AssertionError();
    }
    @Test void allocateReturnAndScrapAreTransactionalAndExistingEditsDoNotDoubleDeduct()throws Exception{
        var s=stock(3);long stockId=s.get("id").asLong();var body=body(s,2,true);var asset=create(body,201);long assetId=asset.get("id").asLong();
        assertEquals(1,current(stockId).get("available").asLong());assertEquals(2,current(stockId).get("used").asLong());
        create(body(s,2,false),409);assertEquals(1,current(stockId).get("available").asLong());
        asset=json.readTree(mvc.perform(post("/api/assets/{id}/return",assetId).with(user(admin())).with(csrf()).header("If-Match",asset.get("version").asLong())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(2,current(stockId).get("paired").asLong());assertEquals(0,current(stockId).get("used").asLong());assertEquals(1,current(stockId).get("available").asLong());
        body.put("assetTag",asset.get("assetTag").asText());
        var updated=json.readTree(mvc.perform(put("/api/assets/{id}",assetId).with(user(admin())).with(csrf()).header("If-Match",asset.get("version").asLong()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(1,current(stockId).get("available").asLong());
        mvc.perform(delete("/api/assets/{id}",assetId).with(user(admin())).with(csrf())).andExpect(status().isConflict());
        body.put("relatedDevices",List.of());
        mvc.perform(put("/api/assets/{id}",assetId).with(user(admin())).with(csrf()).header("If-Match",updated.get("version").asLong()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isConflict());
        mvc.perform(put("/api/assets/{id}",assetId).with(user(admin())).with(csrf()).header("If-Match",updated.get("version").asLong()).header("X-Accessory-Return-Confirmed","true").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isOk());
        assertEquals(3,current(stockId).get("available").asLong());
        var adjustment=Map.of("version",0,"quantity",2,"scrap",true,"note","损坏");
        mvc.perform(post("/api/accessory-stock/{id}/adjust",stockId).with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(adjustment))).andExpect(status().isOk());
        mvc.perform(post("/api/accessory-stock/{id}/adjust",stockId).with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(adjustment))).andExpect(status().isConflict());
        assertEquals(1,current(stockId).get("available").asLong());assertEquals(2,current(stockId).get("scrapped").asLong());
        mvc.perform(get("/api/accessory-stock/{id}",stockId).with(user(admin()))).andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(4));
    }
    @Test void scopeAndPayloadValidationPreserveInventory()throws Exception{
        var s=stock(2);long id=s.get("id").asLong();
        mvc.perform(get("/api/accessory-stock").with(user("editor").authorities(()->"ASSET_EDIT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/accessory-stock/choices").param("companyId",s.get("companyId").asText()).param("locationId",s.get("locationId").asText()).with(user("editor").authorities(()->"ASSET_EDIT"))).andExpect(status().isOk());
        mvc.perform(post("/api/accessory-stock/{id}/adjust",id).with(user(admin())).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isForbidden());
        var body=body(s,1,false);body.put("relatedDevices",List.of(Map.of("name","鼠标","model","伪造品牌","stockId",id,"quantity",1)));create(body,400);
        body=body(s,1,false);var items=(List<?>)body.get("relatedDevices");body.put("relatedDevices",List.of(items.getFirst(),items.getFirst()));create(body,400);
        body=body(s,1,false);var otherCompany=lookups.saveAndFlush(new com.acme.assetmanagement.lookup.LookupValue(com.acme.assetmanagement.lookup.LookupType.COMPANY,"其他公司-"+UUID.randomUUID()));body.put("companyId",otherCompany.getId());create(body,409);
        assertEquals(2,current(id).get("available").asLong());
        mvc.perform(delete("/api/lookups/{id}",s.get("companyId").asLong()).with(user(admin())).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/accessory-stock/{id}/adjust",id).with(user(admin())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")).andExpect(status().isBadRequest());
    }
    @Test void twoConcurrentAllocationsCannotOversell()throws Exception{
        var s=stock(1);var principal=admin();var gate=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{
            var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++){var payload=body(s,1,false);payload.put("assetTag",String.format("%012d",800000000000L+ThreadLocalRandom.current().nextLong(100000000000L)));var bytes=json.writeValueAsBytes(payload);
                futures.add(executor.submit(()->{gate.await();return mvc.perform(post("/api/assets").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(bytes)).andReturn().getResponse().getStatus();}));}
            gate.countDown();var results=new ArrayList<Integer>();for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));Collections.sort(results);assertEquals(List.of(201,409),results);
            assertEquals(0,current(s.get("id").asLong()).get("available").asLong());assertEquals(1,current(s.get("id").asLong()).get("paired").asLong());
        }finally{executor.shutdownNow();}
    }
}
