package devPilot.backend.services.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodeFileFilterTest {

    private final CodeFileFilter filter = new CodeFileFilter();

    @Test
    void acceptsSourceAndDocs() {
        assertThat(filter.isEligible("src/main/java/App.java", 100, 1000)).isTrue();
        assertThat(filter.isEligible("README.md", 100, 1000)).isTrue();
        assertThat(filter.isEligible("Dockerfile", 100, 1000)).isTrue();
    }

    @Test
    void skipsBuildOutputLockfilesHiddenAndLargeFiles() {
        assertThat(filter.isEligible("node_modules/x/index.js", 1, 1000)).isFalse();
        assertThat(filter.isEligible("backend/target/classes/A.java", 1, 1000)).isFalse();
        assertThat(filter.isEligible("client/package-lock.json", 1, 1000)).isFalse();
        assertThat(filter.isEligible(".env", 1, 1000)).isFalse();
        assertThat(filter.isEligible("logo.png", 1, 1000)).isFalse();
        assertThat(filter.isEligible("src/A.java", 5000, 1000)).isFalse();
    }
}
