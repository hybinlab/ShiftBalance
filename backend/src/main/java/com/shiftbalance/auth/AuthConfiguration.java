package com.shiftbalance.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;

@Configuration
public class AuthConfiguration {
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return username -> jdbc.query(
                "SELECT username, password_hash, enabled FROM app_user WHERE username = ?",
                result -> {
                    if (!result.next()) throw new UsernameNotFoundException("User not found");
                    return User.withUsername(result.getString("username"))
                            .password(result.getString("password_hash"))
                            .authorities("ROLE_ADMIN")
                            .disabled(!result.getBoolean("enabled"))
                            .build();
                },
                UsernameNormalizer.normalize(username));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository contextRepository,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        CookieCsrfTokenRepository csrfRepository = new CookieCsrfTokenRepository();
        csrfRepository.setHeaderName("X-CSRF-TOKEN");
        csrfRepository.setCookieCustomizer(cookie -> cookie.httpOnly(true).sameSite("Lax"));
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context
                        .securityContextRepository(contextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/csrf", "/api/auth/session", "/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> response.sendError(401)))
                .logout(logout -> logout.disable())
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${shiftbalance.cors.allowed-origins}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    ApplicationRunner bootstrapAdmin(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            @Value("${shiftbalance.bootstrap-admin.username:}") String configuredUsername,
            @Value("${shiftbalance.bootstrap-admin.password:}") String configuredPassword,
            @Value("${shiftbalance.bootstrap-admin.display-name:}") String configuredDisplayName) {
        return args -> {
            boolean hasUsername = !configuredUsername.isBlank();
            boolean hasPassword = !configuredPassword.isBlank();
            if (!hasUsername && !hasPassword) return;
            if (!hasUsername || !hasPassword) {
                throw new IllegalStateException("Set both SHIFTBALANCE_ADMIN_USERNAME and SHIFTBALANCE_ADMIN_PASSWORD.");
            }

            String username = UsernameNormalizer.normalize(configuredUsername);
            String displayName = configuredDisplayName.isBlank() ? configuredUsername.trim() : configuredDisplayName.trim();
            if (username.length() > 100 || displayName.length() > 100) {
                throw new IllegalStateException("The bootstrap admin username and display name must be at most 100 characters.");
            }
            if (configuredPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalStateException("The bootstrap admin password must be at most 72 UTF-8 bytes.");
            }
            boolean hasExistingUsers = Boolean.TRUE.equals(
                    jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM app_user)", Boolean.class));
            if (!shouldBootstrapAdmin(hasExistingUsers)) return;
            jdbc.update("""
                    INSERT INTO app_user (username, password_hash, display_name)
                    VALUES (?, ?, ?)
                    ON CONFLICT (username) DO NOTHING
                    """, username, encoder.encode(configuredPassword), displayName);
        };
    }

    static boolean shouldBootstrapAdmin(boolean hasExistingUsers) {
        return !hasExistingUsers;
    }
}
