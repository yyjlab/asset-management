package com.acme.assetmanagement.asset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.acme.assetmanagement.asset.RegistrationRosterService.Person;

@RestController
@RequestMapping("/api/registration-roster")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class RegistrationRosterController {
    private final RegistrationRosterService service;
    public RegistrationRosterController(RegistrationRosterService service){this.service=service;}
    public record ImportRequest(@NotEmpty @Size(max=1000) List<@NotNull @Valid Person> people) {}
    @GetMapping public List<RegistrationRosterService.Entry> list(){return service.list();}
    @PostMapping("/import") public RegistrationRosterService.ImportResult importPeople(@RequestBody @Valid ImportRequest request){return service.importPeople(request.people());}
}
