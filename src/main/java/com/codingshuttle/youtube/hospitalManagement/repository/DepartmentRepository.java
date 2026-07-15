package com.codingshuttle.youtube.hospitalManagement.repository;

import com.codingshuttle.youtube.hospitalManagement.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

// Plain CRUD only — Department's @ManyToMany with Doctor (owning side, my_dpt_doctors
// join table) is currently managed through the entity graph, not custom finder methods.
public interface DepartmentRepository extends JpaRepository<Department, Long> {
}