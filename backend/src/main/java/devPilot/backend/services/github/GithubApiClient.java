package devPilot.backend.services.github;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import devPilot.backend.services.github.GitHubException.Kind;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin GitHub REST client.
 *
 * <p>Indexing downloads the whole repository as one zip archive instead of calling the
 * contents API once per file, so a repository costs a handful of API calls no matter how
 * many files it has. That keeps DevPilot usable even without a server token (60 calls/hour).
 */
@Service
@Slf4j
public class GithubApiClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    /** GitHub's compare API lists at most 300 files; beyond that a full re-index is safer. */
    static final int COMPARE_FILE_LIMIT = 300;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final RestClient restClient;
    private final String apiBase;
    private final String serverToken;

    public GithubApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.github.api-base-url:https://api.github.com}") String apiBase,
            @Value("${app.github.token:}") String serverToken) {
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.serverToken = serverToken == null ? "" : serverToken.trim();

        // clone(): the injected builder is a shared singleton; configuring it in place
        // would leak the GitHub token header into every other client built from it.
        RestClient.Builder builder = restClientBuilder.clone()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl(this.apiBase)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "DevPilot");
        // Without a token GitHub still serves public repos, at a lower rate limit.
        if (!this.serverToken.isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + this.serverToken);
        }
        this.restClient = builder.build();
    }

    public Map<String, Object> getRepository(String owner, String repo) {
        return call(() -> restClient.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .body(MAP));
    }

    /** SHA of the latest commit on {@code branch}. */
    public String getHeadSha(String owner, String repo, String branch) {
        Map<String, Object> body = call(() -> restClient.get()
                .uri("/repos/{owner}/{repo}/commits/{ref}", owner, repo, branch)
                .retrieve()
                .body(MAP));
        Object sha = body == null ? null : body.get("sha");
        if (sha == null) {
            throw new GitHubException(Kind.NOT_FOUND, "Could not find branch " + branch + " on GitHub.");
        }
        return String.valueOf(sha);
    }

    /**
     * Files that changed between two commits, or {@code null} when GitHub cannot give a
     * complete answer (history rewritten, or too many files changed) and the caller should
     * fall back to a full re-index.
     */
    @SuppressWarnings("unchecked")
    public List<FileChange> compare(String owner, String repo, String baseSha, String headSha) {
        Map<String, Object> body;
        try {
            body = call(() -> restClient.get()
                    .uri("/repos/{owner}/{repo}/compare/{base}...{head}?per_page=100", owner, repo, baseSha, headSha)
                    .retrieve()
                    .body(MAP));
        } catch (GitHubException ex) {
            if (ex.getKind() == Kind.NOT_FOUND) {
                return null;
            }
            throw ex;
        }
        if (body == null || !"ahead".equals(body.get("status")) || !(body.get("files") instanceof List<?>)) {
            return null;
        }
        List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
        if (files.size() >= COMPARE_FILE_LIMIT) {
            return null;
        }
        List<FileChange> changes = new ArrayList<>();
        for (Map<String, Object> file : files) {
            Object previous = file.get("previous_filename");
            changes.add(new FileChange(
                    String.valueOf(file.get("filename")),
                    String.valueOf(file.get("status")),
                    previous == null ? null : String.valueOf(previous)));
        }
        return changes;
    }

    /**
     * Downloads the repository at {@code ref} as a zip and returns the text files that pass
     * {@code include}, keyed by repository-relative path, in archive order.
     */
    public List<SourceFile> downloadSnapshot(String owner, String repo, String ref,
            Predicate<String> include, long maxFileBytes) {
        URI uri = URI.create(apiBase + "/repos/" + owner + "/" + repo + "/zipball/" + ref);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header(HttpHeaders.USER_AGENT, "DevPilot")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();
        if (!serverToken.isEmpty()) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + serverToken);
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    throw translate(response.statusCode(),
                            response.headers().firstValue("x-ratelimit-remaining").orElse(null),
                            response.headers().firstValue("x-ratelimit-reset").orElse(null));
                }
            }
            return readZip(response.body(), include, maxFileBytes);
        } catch (IOException ex) {
            throw new GitHubException(Kind.UNAVAILABLE, "Could not download the repository from GitHub. Try again in a minute.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitHubException(Kind.UNAVAILABLE, "Download was interrupted.");
        }
    }

    static List<SourceFile> readZip(InputStream in, Predicate<String> include, long maxFileBytes) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                // GitHub wraps everything in a single "owner-repo-sha/" folder.
                String name = entry.getName();
                int slash = name.indexOf('/');
                String path = slash >= 0 ? name.substring(slash + 1) : name;
                if (path.isEmpty() || !include.test(path)) {
                    continue;
                }
                byte[] bytes = zip.readNBytes((int) maxFileBytes + 1);
                if (bytes.length > maxFileBytes || looksBinary(bytes)) {
                    continue;
                }
                files.add(new SourceFile(path, new String(bytes, StandardCharsets.UTF_8)));
            }
        }
        return files;
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException ex) {
            HttpHeaders headers = ex.getResponseHeaders();
            throw translate(ex.getStatusCode().value(),
                    headers == null ? null : headers.getFirst("x-ratelimit-remaining"),
                    headers == null ? null : headers.getFirst("x-ratelimit-reset"));
        } catch (ResourceAccessException ex) {
            throw new GitHubException(Kind.UNAVAILABLE, "GitHub did not respond. Try again in a minute.");
        }
    }

    GitHubException translate(int status, String rateRemaining, String rateReset) {
        if (status == HttpStatus.NOT_FOUND.value()) {
            return new GitHubException(Kind.NOT_FOUND,
                    "Repository not found. Check the owner/name, and note that private repos are not supported.");
        }
        if (status == HttpStatus.CONFLICT.value()) {
            // GitHub answers 409 "Git Repository is empty" for a repo with no commits.
            return new GitHubException(Kind.EMPTY_REPO, "This repository is empty, so there is no code to index yet.");
        }
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            log.error("GitHub rejected the server token (app.github.token). Fix or remove it.");
            return new GitHubException(Kind.BAD_SERVER_TOKEN,
                    "The server's GitHub token was rejected. The site owner needs to update it.");
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()
                || (status == HttpStatus.FORBIDDEN.value() && ("0".equals(rateRemaining) || rateRemaining == null))) {
            Instant retryAt = parseEpoch(rateReset);
            String hint = serverToken.isEmpty()
                    ? " Add a GitHub token on the server to raise the limit from 60 to 5,000 calls an hour."
                    : "";
            return new GitHubException(Kind.RATE_LIMITED,
                    "GitHub's rate limit was reached" + (retryAt != null ? " (resets at " + retryAt + ")" : "") + "." + hint,
                    retryAt);
        }
        return new GitHubException(Kind.UNAVAILABLE, "GitHub returned an error (HTTP " + status + "). Try again in a minute.");
    }

    private static Instant parseEpoch(String value) {
        try {
            return value == null ? null : Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record FileChange(String path, String status, String previousPath) {
        public boolean removed() {
            return "removed".equals(status);
        }
    }

    public record SourceFile(String path, String content) {
    }
}
