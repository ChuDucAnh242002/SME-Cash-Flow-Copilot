package fi.cashflow.copilot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class WebConfiguration {
    @Bean
    UserDetailsService users(@Value("${app.user}") String user, @Value("${app.password}") String password) {
        if (password.length() < 12) throw new IllegalArgumentException("APP_PASSWORD must contain at least 12 characters.");
        return new InMemoryUserDetailsManager(User.withUsername(user)
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("OWNER").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                                        + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                        .referrerPolicy(policy -> policy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .build();
    }
}
