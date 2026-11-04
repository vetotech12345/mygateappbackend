package com.mygateapp.users.entity;

import com.mygateapp.common.MobileNumbers;
import com.mygateapp.common.VersionedEntity;
import com.mygateapp.users.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends VersionedEntity {

    @Column(name = "country_code", nullable = false, length = 5)
    private String countryCode;

    @Column(name = "mobile", nullable = false, length = 15)
    private String mobile;

    @Column(name = "name", length = 60)
    private String name;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "avatar_file_id")
    private UUID avatarFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public boolean isProfileComplete() {
        return name != null && !name.isBlank();
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /** Name for timelines and notifications; never exposes the full mobile number. */
    public String displayName() {
        return isProfileComplete() ? name : "User " + MobileNumbers.mask(mobile);
    }
}
