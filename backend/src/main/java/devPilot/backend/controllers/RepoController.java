package devPilot.backend.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import devPilot.backend.dto.AddPublicRepoRequest;
import devPilot.backend.dto.IndexStatusResponse;
import devPilot.backend.dto.RepositoryResponse;
import devPilot.backend.entity.Repository;
import devPilot.backend.services.RepoService;
import devPilot.backend.services.indexing.IndexingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class RepoController {

    private final RepoService repoService;
    private final IndexingService indexingService;

    @GetMapping
    public List<RepositoryResponse> list() {
        return repoService.listStored();
    }

    @GetMapping("/{id}")
    public RepositoryResponse get(@PathVariable UUID id) {
        return repoService.toResponse(repoService.requireOwned(id));
    }

    @PostMapping("/by-url")
    public ResponseEntity<RepositoryResponse> addByUrl(
            @Valid @RequestBody AddPublicRepoRequest request) {
        Repository repo = repoService.addPublicRepo(request.owner().trim(), request.name().trim());
        return ResponseEntity.ok(repoService.toResponse(repo));
    }

    @PostMapping("/{id}/index")
    public ResponseEntity<RepositoryResponse> index(
            @PathVariable UUID id,
            @RequestHeader("X-Api-Key") String apiKey,
            @RequestHeader(name = "X-AI-Provider", required = false) String provider) {
        Repository repo = indexingService.startIndexing(id, apiKey, provider);
        indexingService.indexAsync(id, apiKey, provider);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @PostMapping("/{id}/pause-index")
    public ResponseEntity<RepositoryResponse> pauseIndex(@PathVariable UUID id) {
        Repository repo = indexingService.pauseIndexing(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @PostMapping("/{id}/resume-index")
    public ResponseEntity<RepositoryResponse> resumeIndex(
            @PathVariable UUID id,
            @RequestHeader("X-Api-Key") String apiKey,
            @RequestHeader(name = "X-AI-Provider", required = false) String provider) {
        Repository repo = indexingService.resumeIndexing(id, apiKey, provider);
        indexingService.resumeAsync(id, apiKey, provider);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @GetMapping("/{id}/status")
    public IndexStatusResponse status(@PathVariable UUID id) {
        return repoService.status(id);
    }
}
