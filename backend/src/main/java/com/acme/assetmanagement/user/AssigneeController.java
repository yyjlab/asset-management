package com.acme.assetmanagement.user;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/assignees")
public class AssigneeController {
    private final UserRepository users;
    public AssigneeController(UserRepository users) { this.users = users; }
    @GetMapping
    @PreAuthorize("hasAuthority('ASSET_VIEW') and hasAuthority('ASSET_CHECKOUT')")
    public List<Assignee> list() {
        return users.findAllByOrderByRoleAscCreatedAtAsc().stream().filter(UserAccount::isEnabled)
                .map(u -> new Assignee(u.getId(), u.getUsername(), u.getDisplayName())).toList();
    }
    public record Assignee(Long id, String username, String displayName) {}
}
