package devPilot.backend.security;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.OAuth2User;

import devPilot.backend.entity.User;

/**
 * Principal for any OAuth2 provider (GitHub, Google, ...).
 * Holds the AppUser upserted for the provider identity.
 */
public class AppUserPrincipal implements OAuth2User {

    private final User user;
    private final Map<String, Object> attributes;
    private final String provider;

    public AppUserPrincipal(User user, Map<String, Object> attributes, String provider) {
        this.user = user;
        this.attributes = attributes;
        this.provider = provider;
    }

    public UUID getId() {
        return user.getId();
    }

    public User getUser() {
        return user;
    }

    public String getProvider() {
        return provider;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AuthorityUtils.createAuthorityList("ROLE_USER");
    }

    @Override
    public String getName() {
        return user.getId().toString();
    }
}
