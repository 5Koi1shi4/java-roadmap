package com.example.security.domain;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    Optional<User> findByUsername(String username);

    Optional<User> findById(long id);

    List<User> findAll();
}
