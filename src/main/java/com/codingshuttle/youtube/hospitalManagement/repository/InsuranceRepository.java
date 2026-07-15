package com.codingshuttle.youtube.hospitalManagement.repository;

import com.codingshuttle.youtube.hospitalManagement.entity.Insurance;
import org.springframework.data.jpa.repository.JpaRepository;

// Plain CRUD — Insurance's lifecycle is actually driven by Patient's owning-side
// cascade=ALL + orphanRemoval (see InsuranceService), so this repository rarely needs
// its own queries; Patient is the entry point for insurance persistence.
public interface InsuranceRepository extends JpaRepository<Insurance, Long> {
}