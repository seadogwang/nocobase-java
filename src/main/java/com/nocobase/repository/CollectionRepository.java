package com.nocobase.repository;

import com.nocobase.entity.CollectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollectionRepository extends JpaRepository<CollectionEntity, Long> {
    Optional<CollectionEntity> findByName(String name);
    boolean existsByName(String name);
    List<CollectionEntity> findByNamespace(String namespace);
}
