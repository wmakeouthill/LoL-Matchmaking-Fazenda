package br.com.lolmatchmaking.backend.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import br.com.lolmatchmaking.backend.domain.entity.Setting;
import java.util.Optional;

public interface SettingRepository extends JpaRepository<Setting, String> {
    Optional<Setting> findByKey(String key);
}
