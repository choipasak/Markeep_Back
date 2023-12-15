package site.markeep.bookmark.auth;

import lombok.*;
import site.markeep.bookmark.user.entity.Role;

@Getter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenUserInfo {

    // 여기가 토큰이 담고 있는 유저 정보를 알려주는 클래스
    // 토큰 안에 있는 유저의 정보를 반환 해 줄것임
    private Long id;

    private String nickname;

    private String email;

    private String password;

    private Role role;

    private boolean autoLogin;


}
