package com.example.security.domain;

import java.util.Optional;

public interface UserRepository {

    Optional<User> findByUsername(String username);
}
