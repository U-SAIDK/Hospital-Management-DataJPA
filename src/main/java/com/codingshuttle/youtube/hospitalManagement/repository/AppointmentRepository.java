package com.codingshuttle.youtube.hospitalManagement.repository;

import com.codingshuttle.youtube.hospitalManagement.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

// No custom queries needed here yet — plain CRUD from JpaRepository covers current
// usage (Appointment rows are created/updated via the managed entities in AppointmentService,
// e.g. dirty-checking in reAssignAppointmentToAnotherDoctor rather than repository calls).
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

}