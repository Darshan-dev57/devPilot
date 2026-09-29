package devPilot.backend.security;

import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Routes to the correct OAuth2UserService based on the client registration ID.
 * Spring Security's userInfoEndpoint only accepts one userService, so we
 * delegate per-provider here.
 */
@Component
@RequiredArgsConstructor
public class DelegatingOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final GithubOAuth2UserService githubOAuth2UserService;
    private final GoogleOAuth2UserService googleOAuth2UserService;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        if ("google".equals(registrationId)) {
            return googleOAuth2UserService.loadUser(userRequest);
        }
        return githubOAuth2UserService.loadUser(userRequest);
    }
}
