package com.alfatgbotik.config;

import com.alfatgbotik.identity.model.AuthenticatedSession;
import com.alfatgbotik.identity.security.SessionAuthenticationFilter;
import com.alfatgbotik.identity.service.SessionService;
import com.alfatgbotik.common.error.ProblemResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    public AuthenticationManager sessionAuthenticationManager(SessionService sessions) {
        return authentication -> {
            if (!(authentication.getCredentials() instanceof String token)) {
                throw new BadCredentialsException("Session is invalid");
            }
            AuthenticatedSession session = sessions.find(token)
                    .orElseThrow(() -> new BadCredentialsException("Session is invalid"));
            return UsernamePasswordAuthenticationToken.authenticated(session, null, List.of());
        };
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, AuthenticationManager authenticationManager,
                                          ProblemResponses problems) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(access -> access
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/telegram").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(request, response, 401, "AUTH_REQUIRED"))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(request, response, 404, "NOT_FOUND")))
                .addFilterBefore(new SessionAuthenticationFilter(authenticationManager, problems),
                        AnonymousAuthenticationFilter.class)
                .build();
    }
}
