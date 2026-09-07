package com.nocobase.repository;

import com.nocobase.entity.RoleResourceScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoleResourceScopeRepository extends JpaRepository<RoleResourceScope, Long> {
    List<RoleResourceScope> findByRoleResourceId(Long roleResourceId);
    List<RoleResourceScope> findByRoleResourceIdAndAction(Long roleResourceId, String action);
}