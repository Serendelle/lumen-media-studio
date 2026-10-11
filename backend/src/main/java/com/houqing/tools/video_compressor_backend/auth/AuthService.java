package com.houqing.tools.video_compressor_backend.auth;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginRateLimiter limiter;
    private final AppProperties props;
    /** Hash of a random value, compared against for unknown usernames so login timing does not reveal them. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       LoginRateLimiter limiter, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.limiter = limiter;
        this.props = props;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public record AuthResult(String token, long expiresIn, UserView user) {}
    public record UserView(long id, String username, String displayName) {
        static UserView of(UserAccount u) { return new UserView(u.getId(), u.getUsername(), u.getDisplayName()); }
    }

    /** Not @Transactional: a single insert, and a failed insert must not poison the follow-up lookup. */
    public AuthResult register(String username, String password, String displayName) {
        if (!props.security().allowRegistration()) throw new ApiException(ErrorCode.REGISTRATION_DISABLED);
        String name = username.trim();
        if (users.existsByUsernameIgnoreCase(name)) throw new ApiException(ErrorCode.USERNAME_TAKEN);
        UserAccount u = new UserAccount();
        u.setUsername(name);
        u.setPasswordHash(encoder.encode(password));
        u.setDisplayName(displayName == null || displayName.isBlank() ? name : displayName.trim());
        u.setCreatedAt(System.currentTimeMillis());
        try {
            users.saveAndFlush(u);
        } catch (org.springframework.dao.DataAccessException e) {
            // concurrent registration of the same name: SQLite reports the UNIQUE violation generically
            if (users.existsByUsernameIgnoreCase(name)) throw new ApiException(ErrorCode.USERNAME_TAKEN);
            throw e;
        }
        return new AuthResult(jwt.issue(u), jwt.ttlSeconds(), UserView.of(u));
    }

    public AuthResult login(String username, String password, String clientIp) {
        String key = clientIp + "|" + username.trim().toLowerCase(Locale.ROOT);
        if (limiter.isBlocked(key)) throw new ApiException(ErrorCode.RATE_LIMITED, "登录失败次数过多，请 10 分钟后再试");
        UserAccount u = users.findByUsernameIgnoreCase(username.trim()).orElse(null);
        boolean ok = encoder.matches(password, u == null ? dummyHash : u.getPasswordHash()) && u != null;
        if (!ok) {
            limiter.recordFailure(key);
            throw new ApiException(ErrorCode.BAD_CREDENTIALS);
        }
        limiter.reset(key);
        return new AuthResult(jwt.issue(u), jwt.ttlSeconds(), UserView.of(u));
    }

    public UserView me(long userId) {
        return users.findById(userId).map(UserView::of).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
