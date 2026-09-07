package com.nocobase.repository;

import com.nocobase.entity.RoleResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoleResourceRepository extends JpaRepository<RoleResource, Long> {
    List<RoleResource> findByRoleName(String roleName);
    Optional<RoleResource> findByRoleNameAndResourceName(String roleName, String resourceName);
    void deleteByRoleNameAndResourceName(String roleName, String resourceName);
}