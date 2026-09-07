package com.nocobase.repository;

import com.nocobase.entity.PluginEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PluginRepository extends JpaRepository<PluginEntity, Long> {
    Optional<PluginEntity> findByName(String name);
    Optional<PluginEntity> findByPackageName(String packageName);
    List<PluginEntity> findByEnabled(Boolean enabled);
    boolean existsByName(String name);
}
