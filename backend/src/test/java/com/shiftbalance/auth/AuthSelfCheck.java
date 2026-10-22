package com.shiftbalance.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

public final class AuthSelfCheck {
    private AuthSelfCheck() {}

    public static void main(String[] args) {
        if (!AuthConfiguration.shouldBootstrapAdmin(false) || AuthConfiguration.shouldBootstrapAdmin(true)) {
            throw new AssertionError("Bootstrap admin creation must stop once the first user exists.");
        }
        if (!UsernameNormalizer.normalize("  Admin.User ").equals("admin.user")) {
            throw new AssertionError("Usernames must be trimmed and case-insensitive.");
        }
        var encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("valid-password");
        if (!encoder.matches("valid-password", hash) || encoder.matches("wrong-password", hash)) {
            throw new AssertionError("Password hashes must verify only the correct password.");
        }
        if (!AuthController.imageContentType(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})
                .equals("image/png")) {
            throw new AssertionError("PNG avatars must be recognized from their signature.");
        }
        if (!AuthController.imageContentType(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})
                .equals("image/jpeg")) {
            throw new AssertionError("JPEG avatars must be recognized from their signature.");
        }
        byte[] webp = new byte[12];
        webp[0] = 'R'; webp[1] = 'I'; webp[2] = 'F'; webp[3] = 'F';
        webp[8] = 'W'; webp[9] = 'E'; webp[10] = 'B'; webp[11] = 'P';
        if (!AuthController.imageContentType(webp).equals("image/webp")) {
            throw new AssertionError("WebP avatars must be recognized from their signature.");
        }
        if (AuthController.imageContentType("not an image".getBytes()) != null) {
            throw new AssertionError("Unrecognized files must not be accepted as avatars.");
        }
        var renamedSession = UsernamePasswordAuthenticationToken.authenticated("renamed-admin", null, List.of());
        if (!renamedSession.getName().equals("renamed-admin")) {
            throw new AssertionError("Renamed sessions must not need a password on their principal.");
        }
    }
}
