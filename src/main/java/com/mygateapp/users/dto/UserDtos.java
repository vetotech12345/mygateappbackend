package com.mygateapp.users.dto;

import com.mygateapp.users.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    /** Same rule as the Flutter validator: letters, spaces, dot, apostrophe, hyphen; must start with a letter. */
    public static final String NAME_PATTERN = "^[A-Za-z][A-Za-z .'-]*$";

    private UserDtos() {
    }

    public record UpdateProfileRequest(
            @Schema(example = "Aarav Mehta")
            @NotBlank(message = "Name is required")
            @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
            @Pattern(regexp = NAME_PATTERN, message = "Name can only contain letters") String name,
            @Email(message = "Enter a valid email address") @Size(max = 254) String email) {
    }

    public record UpdateUserStatusRequest(@NotNull(message = "status is required") UserStatus status,
                                          @Size(max = 200) String reason) {
    }
}
