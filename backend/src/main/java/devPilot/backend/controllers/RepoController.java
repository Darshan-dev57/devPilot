package devPilot.backend.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.services.RepoService;
import devPilot.backend.services.Visitors;
import devPilot.backend.services.indexing.IndexingService;
import devPilot.backend.services.limits.UsageLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Every call carries X-Visitor-Id, a random id the browser keeps. It is not a login: it
 * only scopes the repo list so each visitor sees just the repos they added.
 */
@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class RepoController {

    private final RepoService repoService;
    private final IndexingService indexingService;
    private final UsageLimiter usageLimiter;

    @GetMapping
    public List<RepositoryResponse> list(@RequestHeader(Visitors.HEADER) String visitor) {
        return repoService.listFor(Visitors.parse(visitor));
    }

    @GetMapping("/{id}")
    public RepositoryResponse get(@PathVariable UUID id, @RequestHeader(Visitors.HEADER) String visitor) {
        return repoService.toResponse(repoService.requireVisible(id, Visitors.parse(visitor)));
    }

    @PostMapping("/by-url")
    public ResponseEntity<RepositoryResponse> addByUrl(
            @Valid @RequestBody AddPublicRepoRequest request,
            @RequestHeader(Visitors.HEADER) String visitor) {
        Repository repo = repoService.addPublicRepo(
                request.owner().trim(), request.name().trim(), Visitors.parse(visitor));
        return ResponseEntity.ok(repoService.toResponse(repo));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable UUID id, @RequestHeader(Visitors.HEADER) String visitor) {
        repoService.remove(id, Visitors.parse(visitor));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/index")
    public ResponseEntity<RepositoryResponse> index(
            @PathVariable UUID id,
            @RequestHeader(Visitors.HEADER) String visitor) {
        UUID visitorId = Visitors.parse(visitor);
        Repository current = repoService.requireVisible(id, visitorId);
        // Repos are shared. If someone else is already indexing this one, just follow along.
        if (current.getIndexStatus() == IndexStatus.INDEXING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(current));
        }
        usageLimiter.consume(visitorId, UsageLimiter.Kind.INDEX);
        Repository repo;
        try {
            repo = indexingService.startIndexing(id);
        } catch (RuntimeException ex) {
            usageLimiter.refund(visitorId, UsageLimiter.Kind.INDEX);
            throw ex;
        }
        indexingService.indexAsync(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @PostMapping("/{id}/pause-index")
    public ResponseEntity<RepositoryResponse> pauseIndex(
            @PathVariable UUID id, @RequestHeader(Visitors.HEADER) String visitor) {
        repoService.requireVisible(id, Visitors.parse(visitor));
        Repository repo = indexingService.pauseIndexing(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @PostMapping("/{id}/resume-index")
    public ResponseEntity<RepositoryResponse> resumeIndex(
            @PathVariable UUID id,
            @RequestHeader(Visitors.HEADER) String visitor) {
        repoService.requireVisible(id, Visitors.parse(visitor));
        Repository repo = indexingService.resumeIndexing(id);
        indexingService.resumeAsync(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @GetMapping("/{id}/status")
    public IndexStatusResponse status(@PathVariable UUID id, @RequestHeader(Visitors.HEADER) String visitor) {
        return repoService.status(id, Visitors.parse(visitor));
    }
}
