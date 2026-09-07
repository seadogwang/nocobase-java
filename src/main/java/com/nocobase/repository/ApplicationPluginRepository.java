package com.nocobase.repository;

import com.nocobase.entity.ApplicationPlugin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationPluginRepository extends JpaRepository<ApplicationPlugin, Long> {
    Optional<ApplicationPlugin> findByName(String name);
    List<ApplicationPlugin> findByEnabled(Boolean enabled);
    boolean existsByName(String name);

    @Modifying
    @Transactional
    void deleteByName(String name);
}
