package com.codingshuttle.youtube.hospitalManagement.repository;

import com.codingshuttle.youtube.hospitalManagement.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

// Plain CRUD suffices: DoctorService.getAllDoctors() uses findAll(), and Doctor lookups
// by id come from JpaRepository#findById — no derived/JPQL queries needed so far.

public interface DoctorRepository extends JpaRepository<Doctor, Long> {
}