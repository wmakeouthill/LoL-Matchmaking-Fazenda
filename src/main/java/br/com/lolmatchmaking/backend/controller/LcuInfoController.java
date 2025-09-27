package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.dto.LcuInfo;
import br.com.lolmatchmaking.backend.service.LcuConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal")
public class LcuInfoController {

    private final LcuConfigService cfg;

    public LcuInfoController(LcuConfigService cfg) {
        this.cfg = cfg;
    }

    @PostMapping("/lcu-info")
    public ResponseEntity<Void> post(@RequestBody LcuInfo info) {
        cfg.set(info);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/lcu-info")
    public ResponseEntity<LcuInfo> get() {
        LcuInfo i = cfg.get();
        if (i == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(i);
    }
}

