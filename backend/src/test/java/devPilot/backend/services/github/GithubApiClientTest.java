package devPilot.backend.services.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import devPilot.backend.services.github.GitHubException.Kind;
import devPilot.backend.services.github.GithubApiClient.SourceFile;

class GithubApiClientTest {

    private static byte[] zip(String... nameAndContent) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("acme-demo-abc123/"));
            zip.closeEntry();
            for (int i = 0; i < nameAndContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry("acme-demo-abc123/" + nameAndContent[i]));
                zip.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void readZipStripsTheTopFolderAndSkipsBinaryAndOversizedFiles() throws Exception {
        byte[] archive = zip(
                "src/App.java", "class App {}",
                "img/logo.java", "bin\u0000ary",
                "big.java", "x".repeat(50),
                "notes.txt", "skip me");

        List<SourceFile> files = GithubApiClient.readZip(
                new ByteArrayInputStream(archive), path -> path.endsWith(".java"), 20);

        assertThat(files).extracting(SourceFile::path).containsExactly("src/App.java");
        assertThat(files.get(0).content()).isEqualTo("class App {}");
    }

    @Test
    void translatesGitHubStatusesIntoFriendlyErrors() {
        GithubApiClient anonymous = new GithubApiClient(RestClient.builder(), "https://api.github.com", "");

        assertThat(anonymous.translate(404, null, null).getKind()).isEqualTo(Kind.NOT_FOUND);
        assertThat(anonymous.translate(401, null, null).getKind()).isEqualTo(Kind.BAD_SERVER_TOKEN);
        assertThat(anonymous.translate(409, null, null).getMessage()).startsWith("This repository is empty");

        GitHubException limited = anonymous.translate(403, "0", "1791625830");
        assertThat(limited.getKind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(limited.getRetryAt()).isNotNull();
        assertThat(limited.getMessage()).contains("GitHub token");

        assertThat(anonymous.translate(429, null, null).getKind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(anonymous.translate(503, null, null).getKind()).isEqualTo(Kind.UNAVAILABLE);
    }
}
