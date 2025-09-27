package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.dto.LcuInfo;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class LcuConfigService {
    private final AtomicReference<LcuInfo> info = new AtomicReference<>();

    public void set(LcuInfo i) { info.set(i); }
    public LcuInfo get() { return info.get(); }
    public void clear() { info.set(null); }
}

