package com.nocobase.repository;

import com.nocobase.entity.DataSourceConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DataSourceConfigRepository extends JpaRepository<DataSourceConfigEntity, Long> {

    Optional<DataSourceConfigEntity> findByDsKey(String dsKey);

    boolean existsByDsKey(String dsKey);

    void deleteByDsKey(String dsKey);
}