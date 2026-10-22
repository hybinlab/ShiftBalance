package com.shiftbalance.auth;

import java.util.Locale;

final class UsernameNormalizer {
    private UsernameNormalizer() {}

    static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
