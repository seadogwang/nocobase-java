package com.nocobase.repository;

import com.nocobase.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {
    List<UserRole> findByUserId(Long userId);
    List<UserRole> findByRoleId(Long roleId);
    void deleteByUserId(Long userId);
    List<UserRole> findByRoleIdIn(Collection<Long> roleIds);
    List<UserRole> findByUserIdIn(Collection<Long> userIds);
}