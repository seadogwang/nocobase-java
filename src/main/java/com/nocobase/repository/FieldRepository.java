package com.nocobase.repository;

import com.nocobase.entity.FieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FieldRepository extends JpaRepository<FieldEntity, Long> {
    List<FieldEntity> findByCollectionName(String collectionName);
    List<FieldEntity> findByCollectionNameOrderBySortOrderAsc(String collectionName);
    Optional<FieldEntity> findByCollectionNameAndName(String collectionName, String name);
}
