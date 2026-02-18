package com.rorm.client.research;

import com.rorm.client.research.dto.ResearchResponse;
import com.rorm.client.research.dto.StartResearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/research")
@RequiredArgsConstructor
public class ResearchController {

    private final ResearchService researchService;

    @PostMapping
    @Transactional
    public ResponseEntity<ResearchResponse> startResearch(@Valid @RequestBody StartResearchRequest request) {
        var response = researchService.startResearch(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<ResearchResponse> getResearch(@PathVariable UUID id) {
        var response = researchService.getResearch(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<ResearchResponse>> listResearches() {
        return ResponseEntity.ok(researchService.listResearches());
    }
}
