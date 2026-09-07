package com.nocobase.repository;

import com.nocobase.entity.UiSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UiSchemaRepository extends JpaRepository<UiSchema, Long> {
    Optional<UiSchema> findByUid(String uid);
    List<UiSchema> findAllByUid(String uid);
    Optional<UiSchema> findBySchemaUid(String schemaUid);
    List<UiSchema> findByParentUid(String parentUid);
    List<UiSchema> findByParentUidOrderBySortOrderAsc(String parentUid);
    boolean existsByUid(String uid);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM UiSchema u WHERE u.uid = :uid")
    void deleteByUid(String uid);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM UiSchema u WHERE u.parentUid = :parentUid")
    void deleteByParentUid(String parentUid);
}