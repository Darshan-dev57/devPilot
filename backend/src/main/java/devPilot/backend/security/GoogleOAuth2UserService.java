package devPilot.backend.security;

import java.util.Map;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import devPilot.backend.entity.User;
import devPilot.backend.services.UserService;
import lombok.RequiredArgsConstructor;

/**
 * Google OAuth2 login. Google accounts have no GitHub token, so the user row is
 * created with a placeholder access token — repo features stay GitHub-only.
 */
@Service("googleOAuth2UserService")
@RequiredArgsConstructor
public class GoogleOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String PROVIDER = "google";

    private final UserService userService;
    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User googleUser = delegate.loadUser(userRequest);

        Map<String, Object> attributes = googleUser.getAttributes();
        Long googleId = toLong(attributes.get("sub"));
        String email = String.valueOf(attributes.get("email"));
        String name = attributes.get("name") != null
                ? String.valueOf(attributes.get("name"))
                : email;
        String picture = attributes.get("picture") != null
                ? String.valueOf(attributes.get("picture"))
                : null;

        User user = userService.upsertFromGoogle(googleId, email, name, picture);
        return new AppUserPrincipal(user, attributes, PROVIDER);
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
