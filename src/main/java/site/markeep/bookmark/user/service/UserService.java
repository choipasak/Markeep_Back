package site.markeep.bookmark.user.service;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sun.jdi.InternalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import site.markeep.bookmark.auth.NewRefreshToken;
import site.markeep.bookmark.auth.TokenProvider;
import site.markeep.bookmark.auth.TokenUserInfo;
import site.markeep.bookmark.folder.entity.Folder;
import site.markeep.bookmark.folder.repository.FolderRepository;
import site.markeep.bookmark.follow.repository.FollowRepository;
import site.markeep.bookmark.user.dto.request.GoogleLoginRequestDTO;
import site.markeep.bookmark.user.dto.request.JoinRequestDTO;
import site.markeep.bookmark.user.dto.request.LoginRequestDTO;
import site.markeep.bookmark.user.dto.request.PasswordUpdateRequestDTO;
import site.markeep.bookmark.user.dto.response.LoginResponseDTO;
import site.markeep.bookmark.user.dto.response.ProfileResponseDTO;
import site.markeep.bookmark.user.entity.Role;
import site.markeep.bookmark.user.entity.User;
import site.markeep.bookmark.user.repository.UserRefreshTokenRepository;
import site.markeep.bookmark.user.repository.UserRepository;
import site.markeep.bookmark.user.repository.UserRepositoryImpl;

import javax.persistence.EntityManager;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static site.markeep.bookmark.user.entity.QUser.user;


@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final UserRefreshTokenRepository userRefreshTokenRepository;
    private final TokenProvider tokenProvider;
    private final BCryptPasswordEncoder encoder;
    private final UserRepositoryImpl repoimpl;

    private final JPAQueryFactory queryFactory;
    private final EntityManager em;
    private final FollowRepository followRepository;


    @Value("${upload.path.profile}")
    private String uploadProfilePath;


    @Value("${naver.client_id}")
    private String NAVER_CLIENT_ID;

    @Value("${naver.client_secret}")
    private String NAVER_CLIENT_SECRET;

    @Value("${naver.state")
    private String NAVER_STATE;

    @Value("${google.client_id}")
    private String GOOGLE_CLIENT_ID;

    @Value("${google.client_secret}")
    private String GOOGLE_CLIENT_SECRET;

    @Value("${google.scope}")
    private String GOOGLE_SCOPE;

    public LoginResponseDTO login(LoginRequestDTO dto) throws Exception {

        log.info("로그인 서비스로 넘어옴");

        // 1. dto에서 이메일 값을 뽑아서 가입 여부 확인
        User user = userRepository
                .findByEmail(dto.getEmail())
                .orElseThrow(
                        () -> new RuntimeException("가입된 회원이 아닙니다! 회원 가입을 진행해주세요.")
                );

        log.info("서비스 - dto에서 이메일 비교 성공함");

        // 2. 회원이 맞다면 -> 비밀번호 일치 확인
        String password = dto.getPassword();
        String encodedPassword = user.getPassword();
        if (!encoder.matches(password, encodedPassword)) {
            throw new RuntimeException("비밀번호를 다시 입력해주세요!");
        }

        log.info("서비스 - dto에서 암호화 된 비번 비교 성공");

        // 로그인 성공한 유저에게 제공할 액세스 토큰 생성
        String accessToken = tokenProvider.createAccessToken(user);
        log.info("액세스 토큰 생성 됨");
        log.info("액세스 토큰 : {}", accessToken);
        // 자동로그인 체크 + 로그인 성공한 유저에게 제공할 리프레시 토큰 생성
        String refreshToken = tokenProvider.createRefreshToken();
        log.info("리프레시 토큰 : {}", refreshToken);
        log.info("리프레시 토큰 생성 됨");

        userRefreshTokenRepository.findById(user.getId())
                .ifPresentOrElse(
            it -> it.updateRefreshToken(refreshToken),
            () -> userRefreshTokenRepository.save(new NewRefreshToken(user, refreshToken))
                );
        userRepository.save(User.builder()
                        .id(user.getId())
                        .password(encodedPassword)
                        .nickname(user.getNickname())
                        .email(dto.getEmail())
                        .joinDate(user.getJoinDate())
                        .autoLogin(dto.isAutoLogin())
                        .refreshToken(refreshToken)
                .build());

        // 이거는 이메일 & 비밀번호 둘 다 일치한 경우 화면단으로 보내는 유저의 정보
        return LoginResponseDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .autoLogin(dto.isAutoLogin())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    public void join(JoinRequestDTO dto) {

        String encodedPassword = encoder.encode(dto.getPassword());
        dto.setPassword(encodedPassword);

        User saved = userRepository.save(dto.toEntity(dto));
        folderRepository.save(
                Folder.builder()
                    .user(saved)
                    .title("기본 폴더")
                    .build());
    }

    public boolean isDuplicate(String email) {
        return  userRepository.findByEmail(email).isPresent();
    }
    
    
    public void updatePassword(PasswordUpdateRequestDTO dto) {
        repoimpl.updatePassword(dto);
    }

    public LoginResponseDTO naverLogin(final String code) {
        Map<String, Object> responseData = getNaverAccessToken(code);
        log.info("token: {}", responseData.get("access_token"));


        Map<String, String> userInfo = getNaverUserInfo(responseData.get("access_token"));

        // 중복되지 않았을 경우
        if(!isDuplicate(userInfo.get("response/email"))){
            userRepository.save(User.builder()
                            .email(userInfo.get("response/email"))
                            .password("password!")
                            .nickname(userInfo.get("response/nickname"))
                            .build()
                    );
        }

        // 이미 가입돼 있는 경우
        User foundUser = userRepository.findByEmail(userInfo.get("response/email")).orElseThrow();

        String accessToken = tokenProvider.createAccessToken(foundUser);
        String refreshToken = tokenProvider.createRefreshToken();

        return LoginResponseDTO.builder()
                .id(foundUser.getId())
                .email(foundUser.getEmail())
                .nickname(foundUser.getNickname())
                .autoLogin(foundUser.isAutoLogin())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();

    }

    private Map<String, String> getNaverUserInfo(Object accessToken) {
        // 요청 uri
        String requestUri = "https://openapi.naver.com/v1/nid/me";

        // 요청 헤더
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);

        RestTemplate template = new RestTemplate();
        ResponseEntity<Map> responseEntity = template.exchange(requestUri, HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        Map<String, String> responseData = (Map<String, String>) responseEntity.getBody();

        return responseData;

    }

    private Map<String, Object> getNaverAccessToken(String code) {

        // 요청 uri 설정
        String requestUri = "https://nid.naver.com/oauth2.0/token";

        // 요청 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        // 요청 바디 설정
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", NAVER_CLIENT_ID);
        params.add("client_secret", NAVER_CLIENT_SECRET);
        params.add("code", code);
        params.add("state", NAVER_STATE);
        params.add("service_provider", "NAVER");

        RestTemplate template = new RestTemplate();

        ResponseEntity<Map> ResponseEntity = template.exchange(requestUri, HttpMethod.POST, new HttpEntity<>(params, headers), Map.class);

        Map<String, Object> responseData =  (Map<String, Object>)ResponseEntity.getBody();
        log.info("토큰 요청 응답 데이터! - {}", responseData);

        return responseData;

    }

    // 구글로그인 성공하면 코드 받아서 여기로 넘어옴
    public LoginResponseDTO googleLogin(GoogleLoginRequestDTO dto) {

        String googoleUserEmail = dto.getEmail();
        String googleUserNickname = dto.getNickname();

        // sns로그인 유저에게 지급할 액세스 토큰 생성
        String accessToken = tokenProvider.createAccessToken(User.builder()
                .email(dto.getEmail())
                .nickname(dto.getNickname())
                .role(Role.USER)
                .build());
        // sns로그인 유저에게 지급할 리프레시 토큰 생성
        String refreshToken = tokenProvider.createRefreshToken();

        // 먼저 이미 있는 이메일인지 확인
        if(!userRepository.findByEmail(googoleUserEmail).isPresent()){
            // DB에 없는 이메일이라면
            if (dto.isAutoLogin()) {
                // 자동로그인 체크 한 사람이라면
                // log.warn("여기지금 이메일 없고 자동로그인 체크 OOOOOOOO 사람이야!!!!!!!!!!!!!!!!!!!!");
                User googleLoginUser = userRepository.save(User.builder()
                        .email(googoleUserEmail)
                        .nickname(googleUserNickname)
                        .password("password")
                        .autoLogin(dto.isAutoLogin())
                        .refreshToken(refreshToken)
                        .build());
                return LoginResponseDTO.builder()
                        .id(userRepository.findByEmail(googoleUserEmail).get().getId())
                        .email(googoleUserEmail)
                        .nickname(googleUserNickname)
                        .accessToken(accessToken)
                        .refreshToken(refreshToken)
                        .build();
            }else {
                // log.warn("여기지금 이메일 없고 자동로그인 체크 XXXXXX 사람이야!!!!!!!!!!!!!!!!!!!!");
                // 자동로그인 체크 안한사람이라면
                userRepository.save(User.builder()
                        .email(googoleUserEmail)
                        .nickname(googleUserNickname)
                        .password("password")
                        .autoLogin(dto.isAutoLogin())
                        .build());
                return LoginResponseDTO.builder()
                        .id(userRepository.findByEmail(googoleUserEmail).get().getId())
                        .email(googoleUserEmail)
                        .nickname(googleUserNickname)
                        .accessToken(accessToken)
                        .build();
            }
        }else{ // DB에 있는 이메일이라면
            if(dto.isAutoLogin()){
                // log.warn("여기는 이메일 등록 되어있고!!!!! 자동로그인 체크 OOOOOO 한 사람야!!!!!!!!!!!!!!!");
                // 자동로그인 체크 한 사람이라면 -> 자동 로그인 값과 리프레시토큰 업데이트
                queryFactory.update(user)
                        .set(user.autoLogin, dto.isAutoLogin())
                        .set(user.refreshToken, refreshToken)
                        .where(user.email.eq(dto.getEmail()))
                        .execute();
                em.flush();
                em.clear();
                return LoginResponseDTO.builder()
                        .id(userRepository.findByEmail(googoleUserEmail).get().getId())
                        .email(googoleUserEmail)
                        .nickname(googleUserNickname)
                        .accessToken(accessToken)
                        .refreshToken(refreshToken)
                        .build();

            }else {
                // log.warn("여기는 이메일 등록 되어있고!!!!! 자동로그인 체크 XXXXXXXX 한 사람야!!!!!!!!!!!!!!!");
                // 자동로그인 체크 안 한 사람이라면 -> 자동로그인 값만 업데이트
                queryFactory.update(user)
                        .set(user.autoLogin, dto.isAutoLogin())
                        .where(user.email.eq(dto.getEmail()));
                em.flush();
                em.clear();
                return LoginResponseDTO.builder()
                        .id(userRepository.findByEmail(googoleUserEmail).get().getId())
                        .email(googoleUserEmail)
                        .nickname(googleUserNickname)
                        .accessToken(accessToken)
                        .build();
            }
        }
    }

    public boolean switchFollowBtn(TokenUserInfo userInfo) {
        log.warn("먼저 토큰 안에 id값 있는지부터 보까: {}",userInfo);

        return false;
    }

    //프로필 사진 + 닉네임 + 팔로잉/팔로워 수 + 이메일 값 조회해 온다
    public ProfileResponseDTO getProfile(Long id) {

        Optional<User> user = userRepository.findById(id);
        if (user.isPresent()) {
            ProfileResponseDTO profileResponseDTO = new ProfileResponseDTO();
            profileResponseDTO.setNickname(user.get().getNickname());
            profileResponseDTO.setEmail(user.get().getEmail());
            profileResponseDTO.setFollowerCount(followRepository.countByid_ToId(user.get().getId()));
            profileResponseDTO.setFollowingCount(followRepository.countByid_FromId(user.get().getId()));
            loadFile(profileResponseDTO , user.get().getProfileImage());

            return profileResponseDTO;
        } else {
            throw new RuntimeException("User not found with id: " + id);
        }
    }

    //프로필 사진을 얻어오자
    private ProfileResponseDTO loadFile(ProfileResponseDTO  profileResponseDTO , String profileImg) {
        if(profileImg == null ) return profileResponseDTO;
        try {
            String filePath
                    = uploadProfilePath + "/" + profileImg;
            // 2. 얻어낸 파일 경로를 통해 실제 파일 데이터를 로드하기.
            File folderfileFile = new File(filePath);

            // 모든 사용자가 프로필 사진을 가지는 것은 아니다. -> 프사가 없는 사람들은 경로가 존재하지 않을 것이다.
            // 만약 존재하지 않는 경로라면 클라이언트로 404 status를 리턴.
            if(!folderfileFile.exists()) {
                throw new FileNotFoundException("등록된 이지미가 없습니다.");
            }

            // 해당 경로에 저장된 파일을 바이트 배열로 직렬화 해서 리턴.
            byte[] fileData = FileCopyUtils.copyToByteArray(folderfileFile);

            // 3. 응답 헤더에 컨텐츠 타입을 설정.
            HttpHeaders headers = new HttpHeaders();
            MediaType contentType = findExtensionAndGetMediaType(filePath);
            if(contentType == null) {
                throw new InternalException("발견된 파일은 이미지 파일이 아닙니다.");
            }

            headers.setContentType(contentType);
            profileResponseDTO.setHeaders(headers);
            profileResponseDTO.setFileData(fileData);

            return  profileResponseDTO;

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("파일을 찾을 수 없습니다.");
        }
    }

    private MediaType findExtensionAndGetMediaType(String filePath) {

        // 파일 경로에서 확장자 추출하기
        // C:/todo_upload/nsadjknjkncjndnjs_abc.jpg
        String ext
                = filePath.substring(filePath.lastIndexOf(".") + 1);

        // 추출한 확장자를 바탕으로 MediaType을 설정. -> Header에 들어갈 Content-type이 됨.
        switch (ext.toUpperCase()) {
            case "JPG": case "JPEG":
                return MediaType.IMAGE_JPEG;
            case "PNG":
                return MediaType.IMAGE_PNG;
            case "GIF":
                return MediaType.IMAGE_GIF;
            default:
                return null;
        }
    }
}

