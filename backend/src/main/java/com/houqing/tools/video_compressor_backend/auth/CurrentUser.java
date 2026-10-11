package com.houqing.tools.video_compressor_backend.auth;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Resolves the authenticated user id from the JWT in the security context. */
public final class CurrentUser {
    private CurrentUser() {}

    /** The authenticated user id, or empty for anonymous requests (e.g. ticket-based downloads). */
    public static java.util.Optional<Long> optionalId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            try {
                return java.util.Optional.of(Long.parseLong(jwt.getSubject()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return java.util.Optional.empty();
    }

    public static long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            try {
                return Long.parseLong(jwt.getSubject());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new ApiException(ErrorCode.UNAUTHORIZED);
    }
}
