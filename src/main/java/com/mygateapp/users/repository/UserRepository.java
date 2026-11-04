package com.mygateapp.users.repository;

import com.mygateapp.users.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByCountryCodeAndMobile(String countryCode, String mobile);
}
