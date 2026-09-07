package com.nocobase.repository;

import com.nocobase.entity.RoleResourceAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoleResourceActionRepository extends JpaRepository<RoleResourceAction, Long> {
    List<RoleResourceAction> findByRoleResourceId(Long roleResourceId);
}