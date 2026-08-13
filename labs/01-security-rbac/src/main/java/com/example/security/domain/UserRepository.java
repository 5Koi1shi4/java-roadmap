package com.example.security.domain;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    Optional<User> findByUsername(String username);

    List<User> findAll();
}
