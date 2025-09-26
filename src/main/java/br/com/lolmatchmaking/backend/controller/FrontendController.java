package br.com.lolmatchmaking.backend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@Controller
public class FrontendController {

    /**
     * Serve o index.html do frontend para rotas específicas do Angular
     */
    @GetMapping({"/", "/queue", "/draft", "/match/**", "/admin", "/config"})
    public String index(HttpServletRequest request) {
        String path = request.getRequestURI();
        log.debug("🌐 Servindo frontend para rota: {}", path);
        return "forward:/index.html";
    }

    /**
     * ✅ NOVO: Redirect da rota /api/ para a raiz (melhora UX)
     * Quando usuário acessa http://localhost:8080/api/ diretamente
     */
    @GetMapping("/api/")
    public String redirectApiToRoot() {
        log.debug("🔄 Redirecionando /api/ para raiz");
        return "redirect:/";
    }
}
