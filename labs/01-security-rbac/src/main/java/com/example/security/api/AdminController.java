package com.example.security.api;

import com.example.security.application.RbacService;
import com.example.security.domain.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public final class AdminController {

    private final UserRepository users;
    private final RbacService rbac;

    public AdminController(UserRepository users, RbacService rbac) {
        this.users = users;
        this.rbac = rbac;
    }

    @GetMapping("/users")
    public List<UserSummary> listUsers() {
        return users.findAll().stream()
                .map(user -> new UserSummary(user.id(), user.username(), user.enabled()))
                .toList();
    }

    @PostMapping("/users/{userId}/roles/{roleId}")
    public ResponseEntity<Void> grantRole(
            @PathVariable long userId,
            @PathVariable long roleId
    ) {
        rbac.grantRole(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    public record UserSummary(long id, String username, boolean enabled) {
    }
}
