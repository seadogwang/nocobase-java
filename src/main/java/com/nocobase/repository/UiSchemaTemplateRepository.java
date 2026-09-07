package com.nocobase.repository;

import com.nocobase.entity.UiSchemaTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UiSchemaTemplateRepository extends JpaRepository<UiSchemaTemplate, Long> {
    Optional<UiSchemaTemplate> findByName(String name);
    boolean existsByName(String name);
}