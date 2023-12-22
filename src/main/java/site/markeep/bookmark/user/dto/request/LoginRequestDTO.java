package site.markeep.bookmark.user.dto.request;

import lombok.*;
import site.markeep.bookmark.user.entity.Role;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;

@Getter @Setter
@ToString @EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginRequestDTO {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    @NotBlank
    private Role role;

    @NotBlank
    private boolean autoLogin;

}
