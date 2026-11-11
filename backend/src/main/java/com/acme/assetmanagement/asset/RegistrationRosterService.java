package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.audit.*;
import com.acme.assetmanagement.common.ApiException;
import com.acme.assetmanagement.integration.deployment.DeploymentIntakeRepository;
import jakarta.validation.constraints.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RegistrationRosterService {
    public record Person(@NotBlank @Size(max=120) String name, @Size(max=120) String department, @Size(max=20) String seat) {}
    public record ImportResult(int added, int skipped, List<String> duplicates) {}
    public record Computer(Long id, String assetTag, String name) {}
    public record Entry(String id, String name, String department, String seat, String state, String reason, List<Computer> computers, int pendingCount) {}
    private final RegistrationRosterRepository roster;
    private final AssetRepository assets;
    private final DeploymentIntakeRepository intakes;
    private final AuditLogService audit;
    private final TransactionTemplate transaction;
    private static final Set<String> COMPUTERS=Set.of("笔记本电脑","笔记本","笔记本计算机","台式电脑","台式","台式机","台式计算机","桌面电脑");
    public RegistrationRosterService(RegistrationRosterRepository roster, AssetRepository assets, DeploymentIntakeRepository intakes, AuditLogService audit, PlatformTransactionManager manager) {
        this.roster=roster; this.assets=assets; this.intakes=intakes; this.audit=audit; this.transaction=new TransactionTemplate(manager);
    }
    static String clean(String value) { return value==null ? "" : value.strip(); }
    static String key(String name) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(clean(name).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public ImportResult importPeople(List<Person> people) {
        if(people==null || people.isEmpty() || people.size()>1000) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"每次请导入1至1000人");
        for(var person:people) if(person==null || clean(person.name()).isEmpty() || person.name().length()>120
                || clean(person.department()).length()>120 || clean(person.seat()).length()>20 || "空置".equals(clean(person.name()))
                || clean(person.name()).codePoints().anyMatch(Character::isISOControl))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"姓名必填且不能是空置；姓名和部门最多120字符，座位号最多20字符");
        // The unique name hash also protects simultaneous imports; retry only a confirmed duplicate race.
        for(int attempt=0;attempt<3;attempt++) {
            try { return transaction.execute(status -> {
                var ids=new HashSet<>(roster.findAll().stream().map(row->row.id).toList());
                var duplicates=new ArrayList<String>(); int added=0;
                for(var person:people) {
                    String id=key(person.name());
                    if(!ids.add(id)){duplicates.add(clean(person.name()));continue;}
                    var row=new RegistrationRoster(); row.id=id; row.name=clean(person.name()); row.department=clean(person.department()); row.seat=clean(person.seat()); row.createdAt=Instant.now();
                    roster.save(row); added++;
                }
                roster.flush();
                audit.success(AuditAction.REGISTRATION_ROSTER_IMPORT,"REGISTRATION_ROSTER",null,"登记核对名单","导入登记核对名单",Map.of("added",added,"skipped",duplicates.size()));
                return new ImportResult(added,duplicates.size(),duplicates);
            }); } catch(DataIntegrityViolationException | ConcurrencyFailureException e) {
                if(attempt==2) throw new ApiException(HttpStatus.CONFLICT,"名单并发导入冲突，请重试；未重复添加人员");
            }
        }
        throw new IllegalStateException();
    }
    @Transactional(readOnly=true)
    public List<Entry> list() {
        var owned=assets.registrationRosterAssets().stream().filter(a->COMPUTERS.contains(Normalizer.normalize(a.getCategory(),Normalizer.Form.NFKC).replaceAll("\\s+","")))
                .collect(Collectors.groupingBy(a->clean(a.getAssignee())));
        var pending=intakes.pendingReportedPeople().stream().collect(Collectors.groupingBy(p->clean(p.getName())));
        return roster.findAll().stream().map(person->{
            var matches=owned.getOrDefault(person.name,List.of());
            var usable=matches.stream().filter(a->a.getCheckedOut() && !a.getStatus().contains("报废")).toList();
            var waiting=pending.getOrDefault(person.name,List.of());
            boolean departmentMismatch=!person.department.isBlank() && (usable.stream().anyMatch(a->!clean(a.getDepartment()).isEmpty() && !person.department.equals(clean(a.getDepartment())))
                    || waiting.stream().anyMatch(p->!clean(p.getDepartment()).isEmpty() && !person.department.equals(clean(p.getDepartment()))));
            String state=departmentMismatch ? "confirm" : !usable.isEmpty() ? "registered" : !waiting.isEmpty() ? "pending" : !matches.isEmpty() ? "confirm" : "missing";
            String reason=departmentMismatch ? "同名记录部门不一致，请核对是否同一人" : state.equals("confirm") ? "仅匹配到报废或领用状态异常的电脑" : "";
            return new Entry(person.id,person.name,person.department,person.seat,state,reason,matches.stream().map(a->new Computer(a.getId(),a.getAssetTag(),a.getName())).toList(),waiting.size());
        }).sorted(Comparator.comparingInt((Entry e)->switch(e.state()){case "missing"->0;case "confirm"->1;case "pending"->2;default->3;})
                .thenComparing(Entry::seat,Comparator.comparingInt(RegistrationRosterService::seatNumber).thenComparing(Comparator.naturalOrder())).thenComparing(Entry::name)).toList();
    }
    private static int seatNumber(String value) { try{return Integer.parseInt(value);}catch(NumberFormatException e){return Integer.MAX_VALUE;} }
}
