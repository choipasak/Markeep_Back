package site.markeep.bookmark.user.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import site.markeep.bookmark.auth.TokenProvider;
import site.markeep.bookmark.auth.TokenUserInfo;
import site.markeep.bookmark.user.dto.SnsLoginDTO;
import site.markeep.bookmark.user.dto.request.GoogleLoginRequestDTO;
import site.markeep.bookmark.user.dto.request.JoinRequestDTO;
import site.markeep.bookmark.user.dto.request.LoginRequestDTO;
import site.markeep.bookmark.user.dto.request.PasswordUpdateRequestDTO;
import site.markeep.bookmark.user.dto.response.LoginResponseDTO;
import site.markeep.bookmark.user.dto.response.ProfileResponseDTO;
import site.markeep.bookmark.user.entity.Role;
import site.markeep.bookmark.user.repository.UserRepository;
import site.markeep.bookmark.user.service.UserService;
import site.markeep.bookmark.util.MailService;

import java.io.IOException;
import java.util.UUID;


@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/user")
@CrossOrigin
public class UserController {


    private final UserService userService;
    private final MailService mailService;
    private final UserRepository userRepository;
    private final TokenProvider tokenProvider;

    @GetMapping("/status")
    public ResponseEntity<?> testToken(
            @RequestHeader("Authorization") String token
    ){
        boolean isExpired;
        if(StringUtils.hasText(token) && token.startsWith("Bearer")){
            isExpired = tokenProvider.isTokenExpired(token.substring(7)); //Bearer 다음의 문자열만을 추출하여 전달
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build(); //잘못된 토큰 형식의 경우 badRequest 응답
        }
        return ResponseEntity.ok(isExpired); //isExpired 값을 body에 담아서 전달
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequestDTO dto){

        try {
            LoginResponseDTO responseDTO = userService.login(dto);
            return ResponseEntity.ok().body(responseDTO);
        } catch (Exception e){
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @PostMapping("/join")
    public ResponseEntity<?> join(
            @Validated @RequestBody JoinRequestDTO dto,
            BindingResult result
    ) {
        log.info("/user/join POST! ");
        log.info("JoinRequestDTO: {}", dto);

        if(result.hasErrors()){
            log.warn(result.toString());
            return ResponseEntity.badRequest().body(result.getFieldError());
        }

        try {
            userService.join(dto);
            return ResponseEntity.status(HttpStatus.OK).build();
        } catch (Exception e) {
            log.warn("기타 예외가 발생했습니다.");
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @GetMapping("/join")
    public ResponseEntity<?> checkingDuplicateEmailInDBBysendingVerifycationCodeToMail(String email) {
        //이메일을 입력하지 않은 경우 빈 문자열 반환-400 오류
        if(email.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("");
        }
        log.info("{} 중복?? - {}", email, userService.isDuplicate(email));
        // 400 오류
        if(userService.isDuplicate(email)) {
            return ResponseEntity.badRequest()
                    .body("이미 가입된 이메일 입니다.");
        }
        //인증번호 반환 : - 200 ok
        return ResponseEntity.ok().body(mailService.sendMail(email));
    }

    //password 재 설정시 인증번호 전송
    @PutMapping("/password")
    public ResponseEntity<?> authorizingByUsersEmailBeforeUpdatePassword(String email) {

        if(email.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        // 400 오류
        if(!userService.isDuplicate(email)) {
            return ResponseEntity.badRequest()
                    .body("미가입 이메일 입니다.");
        }
        //인증번호 반환 : - 200 ok
        return ResponseEntity.ok().body(mailService.sendMail(email));
    }

    @PatchMapping("/password")
    public ResponseEntity<?> updatePassword(@RequestBody PasswordUpdateRequestDTO dto){
        userService.updatePassword(dto);
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @PostMapping("/google-login")
    public ResponseEntity<?> googleSignIn(@RequestBody GoogleLoginRequestDTO dto) {
        try {
            LoginResponseDTO responseDTO = userService.googleLogin(dto);
            return ResponseEntity.ok().body(responseDTO);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }


    @GetMapping("/naver-login")
    public ResponseEntity<?> naverLogin(@RequestBody SnsLoginDTO dto){
        log.info("user/naver-login - GET! -code:{}", dto);

        try {
            LoginResponseDTO responseDTO = userService.naverLogin(dto);
            return ResponseEntity.ok().body(responseDTO);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @GetMapping("kakao-login")
    public ResponseEntity<?> kakaoLogin(@RequestBody SnsLoginDTO dto) {
        log.info("user/kakao-login - GET! -code:{}", dto);
        LoginResponseDTO responseDTO = userService.kakaoService(dto);

        return ResponseEntity.ok().body(responseDTO);
    }

    //프로필 사진 + 닉네임 + 팔로잉/팔로워 수 + 이메일 값 조회해오는 요청
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(@AuthenticationPrincipal TokenUserInfo userInfo){

        try {
            ProfileResponseDTO profile = userService.getProfile(userInfo.getId());
            return ResponseEntity.ok().body(profile);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    //프로필 사진 등록
    @PostMapping("/profile")
    public ResponseEntity<?> addProfile(
            @AuthenticationPrincipal TokenUserInfo userInfo,
            @RequestPart(value = "profileImage", required = false) MultipartFile profileImg
    ){
        log.warn("/user/profile - POST 요청! :{}", profileImg);

        // 400 오류
        if(userInfo.getId() == null) {
            return ResponseEntity.badRequest()
                    .body("가입된 회원이 아닙니다.");
        }

        try {
            String uploadedFilePath = null;
            if(profileImg != null) {
                log.info("attached file name: {}", profileImg.getOriginalFilename());
                // 전달받은 프로필 이미지를 먼저 지정된 경로에 저장한 후 DB 저장을 위해 경로를 받아오자.
                uploadedFilePath = userService.uploadProfileImage(profileImg);
            }
            int modifyCnt = userService.create(userInfo.getId(),uploadedFilePath);
            return ResponseEntity.ok()
                    .body(modifyCnt);
        } catch (Exception e) {
            log.warn("기타 예외가 발생했습니다!");
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }

    }


    //닉네임 수정
    @PutMapping("/nickname")
    public ResponseEntity<?> modifyNickname(
            @AuthenticationPrincipal TokenUserInfo userInfo,
            String nickname
    ){
        // 400 오류
        if(userInfo.getId() == null) {
            return ResponseEntity.badRequest()
                    .body("가입된 회원이 아닙니다.");
        }

        try {
            int modifyCnt = userService.modifyNickname(userInfo.getId(), nickname);
            return ResponseEntity.ok()
                    .body(modifyCnt);
        } catch (Exception e) {
            log.warn("기타 예외가 발생했습니다!");
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }

    }

}
