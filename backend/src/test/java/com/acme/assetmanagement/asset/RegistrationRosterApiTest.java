package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.lookup.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:roster-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationRosterApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RegistrationRosterRepository roster;
    @Autowired AssetRepository assets;
    @Autowired LookupRepository lookups;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @BeforeEach void prepare(){roster.deleteAll();}
    ResultActions importPeople(List<?> people) throws Exception {
        return mvc.perform(post("/api/registration-roster/import").with(user("admin").roles("SUPER_ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("people",people))));
    }
    Map<String,String> person(String name,String department,String seat){return Map.of("name",name,"department",department,"seat",seat);}
    @Test void importsOnlyNewNamesAndPreservesOriginalDepartmentAndSeat() throws Exception {
        long count=assets.count();
        importPeople(List.of(person(" 张三 ","研发部","1"),person("张三","销售部","2"),person("李四","销售部","3")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.added").value(2)).andExpect(jsonPath("$.skipped").value(1));
        importPeople(List.of(person("张三","新部门","99"))).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(0));
        var original=roster.findById(RegistrationRosterService.key("张三")).orElseThrow();
        assertEquals("研发部",original.department); assertEquals("1",original.seat); assertEquals(count,assets.count());
        assertEquals(2,roster.count());
    }
    @Test void permissionsCsrfAndValidationAreIndependent() throws Exception {
        mvc.perform(get("/api/registration-roster")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/registration-roster").with(user("employee").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/registration-roster/import").with(user("admin").roles("SUPER_ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        importPeople(List.of(person("正常","","1"),person("空置","","2"))).andExpect(status().isUnprocessableEntity());
        assertEquals(0,roster.count());
        importPeople(List.of(person("x".repeat(121),"",""))).andExpect(status().isBadRequest());
    }
    LookupValue option(LookupType type,String name){return lookups.findByTypeAndNameIgnoreCase(type,name).orElseGet(()->lookups.saveAndFlush(new LookupValue(type,name)));}
    Asset computer(String owner,String department,String category,String status,boolean checkedOut){
        var seed=assets.findAll().getFirst(); var row=new Asset();
        row.setAssetTag("R"+UUID.randomUUID().toString().substring(0,10)); row.setName("测试电脑"); row.setCompany(seed.getCompany()); row.setModel(seed.getModel()); row.setLocation(seed.getLocation());
        row.setCategory(option(LookupType.CATEGORY,category)); row.setStatus(option(LookupType.STATUS,status)); row.setCheckedOut(checkedOut); row.setAssignedTo(owner); row.setOwnershipDepartment(department);
        return assets.saveAndFlush(row);
    }
    @Test void checksEveryOwnedComputerButNotMiceAndFlagsDepartmentOrScrappedConflicts() throws Exception {
        String prefix=UUID.randomUUID().toString();
        importPeople(List.of(person(prefix+"一","研发部","1"),person(prefix+"二","研发部","2"),person(prefix+"三","研发部","3"),person(prefix+"四","研发部","4"))).andExpect(status().isOk());
        computer(prefix+"一","研发部","笔记本电脑","在用",true);
        computer(prefix+"一","研发部","台式电脑","在用",true);
        computer(prefix+"二","研发部","鼠标","在用",true);
        computer(prefix+"三","销售部","台式电脑","在用",true);
        computer(prefix+"四","研发部","笔记本电脑","已报废",true);
        var response=mvc.perform(get("/api/registration-roster").with(user("admin").roles("SUPER_ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        var rows=json.readTree(response.getContentAsByteArray());
        var states=new HashMap<String,String>(); rows.forEach(row->states.put(row.get("name").asText(),row.get("state").asText()));
        assertEquals("registered",states.get(prefix+"一")); assertEquals("missing",states.get(prefix+"二"));
        assertEquals("confirm",states.get(prefix+"三")); assertEquals("confirm",states.get(prefix+"四"));
        for(var row:rows)if(row.get("name").asText().equals(prefix+"一"))assertEquals(2,row.get("computers").size());
    }
    @Test void pendingCollectionIsNotAnApprovedAsset() throws Exception {
        String person="待审核"+UUID.randomUUID().toString(); importPeople(List.of(person(person,"研发部","10"))).andExpect(status().isOk());
        jdbc.update("insert into ad_deployment_intakes (external_id,persistence_version,fingerprint,credential_id,received_at,state,revision,request_json,reported_user_name,reported_department) values (?,0,?,?,CURRENT_TIMESTAMP,'pending_review',0,'{}',?,?)",
            "pending:"+UUID.randomUUID(),"0".repeat(64),UUID.randomUUID().toString(),person,"研发部");
        mvc.perform(get("/api/registration-roster").with(user("admin").roles("SUPER_ADMIN"))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].state").value("pending")).andExpect(jsonPath("$[0].pendingCount").value(1));
    }
    @Test void simultaneousImportsDoNotDuplicateOrOverwritePeople() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();return importPeople(List.of(person("并发测试","研发部","1"))).andReturn().getResponse().getStatus();}));
            start.countDown(); for(var task:tasks)assertEquals(200,task.get(30,TimeUnit.SECONDS));
        }
        assertEquals(1,roster.count());
    }
}
