package br.com.lolmatchmaking.backend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@Controller
public class FrontendController {

    /**
     * Serve o index.html do frontend para todas as rotas que não são API
     */
    @GetMapping({"/", "/queue", "/draft", "/match/**", "/admin", "/config"})
    public String index(HttpServletRequest request) {
        String path = request.getRequestURI();
        log.debug("🌐 Servindo frontend para rota: {}", path);
        return "forward:/index.html";
    }

    /**
     * Catch-all para SPAs - qualquer rota não capturada vai para o frontend
     */
    @RequestMapping(value = "/{path:[^\\.]*}")
    public String catchAll() {
        return "forward:/index.html";
    }
}
