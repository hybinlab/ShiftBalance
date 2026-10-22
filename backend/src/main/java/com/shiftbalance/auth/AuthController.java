package com.shiftbalance.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository contextRepository,
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken());
    }

    @GetMapping("/session")
    public SessionResponse session(Authentication authentication) {
        if (!isAuthenticated(authentication)) return new SessionResponse(false, null, null);
        return sessionResponse(authentication.getName());
    }

    @GetMapping("/profile")
    public ProfileResponse profile(Authentication authentication) {
        return profileResponse(currentUsername(authentication));
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateUsername(
            @Valid @RequestBody UsernameRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String currentUsername = currentUsername(authentication);
        String username = UsernameNormalizer.normalize(request.username());
        try {
            int updated = jdbc.update("UPDATE app_user SET username = ? WHERE username = ?", username, currentUsername);
            if (updated == 0) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("当前登录账号已失效，请重新登录"));
            }
        } catch (DuplicateKeyException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("该用户名已被使用"));
        }

        Authentication updatedAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                username, null, authentication.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(updatedAuthentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, httpRequest, httpResponse);
        return ResponseEntity.ok(profileResponse(username));
    }

    @PutMapping("/profile/password")
    public ResponseEntity<?> updatePassword(
            @Valid @RequestBody PasswordRequest request,
            Authentication authentication) {
        if (request.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.badRequest().body(new ErrorResponse("密码不能超过 72 个 UTF-8 字节"));
        }
        String username = currentUsername(authentication);
        List<String> passwordHashes = jdbc.query(
                "SELECT password_hash FROM app_user WHERE username = ?",
                (result, row) -> result.getString("password_hash"), username);
        if (passwordHashes.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("当前登录账号已失效，请重新登录"));
        }
        String passwordHash = passwordHashes.getFirst();
        if (!passwordEncoder.matches(request.currentPassword(), passwordHash)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("当前密码不正确"));
        }
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE username = ?",
                passwordEncoder.encode(request.newPassword()), username);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(value = "/profile/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> updateAvatar(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) throws IOException {
        if (file.isEmpty()) return ResponseEntity.badRequest().body(new ErrorResponse("请选择头像文件"));
        if (file.getSize() > MAX_AVATAR_BYTES) {
            return ResponseEntity.badRequest().body(new ErrorResponse("头像文件不能超过 2 MB"));
        }
        byte[] bytes = file.getBytes();
        String contentType = imageContentType(bytes);
        if (contentType == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("头像只支持 JPEG、PNG 或 WebP 图片"));
        }
        String username = currentUsername(authentication);
        int updated = jdbc.update("UPDATE app_user SET avatar_data = ?, avatar_content_type = ? WHERE username = ?",
                bytes, contentType, username);
        if (updated == 0) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("当前登录账号已失效，请重新登录"));
        }
        return ResponseEntity.ok(profileResponse(username));
    }

    @GetMapping("/profile/avatar")
    public ResponseEntity<byte[]> avatar(Authentication authentication) {
        String username = currentUsername(authentication);
        List<Avatar> avatars = jdbc.query(
                "SELECT avatar_data, avatar_content_type FROM app_user WHERE username = ?",
                (result, row) -> new Avatar(result.getBytes("avatar_data"), result.getString("avatar_content_type")),
                username);
        if (avatars.isEmpty() || avatars.getFirst().data() == null) {
            return ResponseEntity.noContent().build();
        }
        Avatar avatar = avatars.getFirst();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(avatar.data());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("账号或密码错误"));
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            UsernameNormalizer.normalize(request.username()), request.password()));
        } catch (AuthenticationException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("账号或密码错误"));
        }

        httpRequest.getSession(true);
        httpRequest.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, httpRequest, httpResponse);
        return ResponseEntity.ok(sessionResponse(authentication.getName()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    private SessionResponse sessionResponse(String username) {
        return new SessionResponse(true, username, username);
    }

    private ProfileResponse profileResponse(String username) {
        List<Boolean> avatarAvailability = jdbc.query(
                "SELECT avatar_data IS NOT NULL FROM app_user WHERE username = ?",
                (result, row) -> result.getBoolean(1), username);
        if (avatarAvailability.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "当前登录账号已失效，请重新登录");
        }
        return new ProfileResponse(username, avatarAvailability.getFirst());
    }

    private String currentUsername(Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return authentication.getName();
    }

    static String imageContentType(byte[] image) {
        if (image.length >= 8
                && (image[0] & 0xff) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G'
                && image[4] == 0x0d && image[5] == 0x0a && image[6] == 0x1a && image[7] == 0x0a) {
            return "image/png";
        }
        if (image.length >= 3 && (image[0] & 0xff) == 0xff && (image[1] & 0xff) == 0xd8 && (image[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (image.length >= 12
                && image[0] == 'R' && image[1] == 'I' && image[2] == 'F' && image[3] == 'F'
                && image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank String password) {}
    public record UsernameRequest(@NotBlank @Size(min = 3, max = 100)
                                  @Pattern(regexp = "[A-Za-z0-9._-]{3,100}") String username) {}
    public record PasswordRequest(@NotBlank String currentPassword,
                                  @NotBlank @Size(min = 10, max = 100) String newPassword) {}
    public record CsrfResponse(String csrfToken) {}
    public record ProfileResponse(String username, boolean avatarAvailable) {}
    public record SessionResponse(boolean authenticated, String username, String displayName) {}
    public record ErrorResponse(String message) {}
    private record Avatar(byte[] data, String contentType) {}
}
