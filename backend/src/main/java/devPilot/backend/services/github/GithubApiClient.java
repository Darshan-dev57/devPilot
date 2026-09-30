package devPilot.backend.services.github;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GithubApiClient {

    private static final String API_BASE = "https://api.github.com";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient.Builder restClientBuilder;

    @Value("${app.github.token}")
    private String serverToken;

    public Map<String, Object> getRepository(String owner, String repo) {
        return client()
                .get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .body(MAP);
    }

    public Map<String, Object> getRepoTree(String owner, String repo, String branch) {
        return client()
                .get()
                .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                .retrieve()
                .body(MAP);
    }

    public String getFileContent(String owner, String repo, String path) {
        Map<String, Object> body = client()
                .get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                .retrieve()
                .body(MAP);
        if (body == null) {
            return null;
        }
        Object encoding = body.get("encoding");
        Object content = body.get("content");
        if (content == null) {
            return null;
        }
        if ("base64".equals(String.valueOf(encoding))) {
            String raw = String.valueOf(content).replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        }
        return String.valueOf(content);
    }

    private RestClient client() {
        return restClientBuilder
                .baseUrl(API_BASE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serverToken)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "DevPilot")
                .build();
    }
}
