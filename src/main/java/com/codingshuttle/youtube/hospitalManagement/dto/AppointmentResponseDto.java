package com.codingshuttle.youtube.hospitalManagement.dto;

import lombok.Data;

import java.time.LocalDateTime;

// API-boundary shape for Appointment, mapped from the entity via ModelMapper in the service
// layer — decouples the wire contract from Appointment's JPA associations (patient/doctor are
// EAGER/LAZY proxies respectively; serializing them directly risks LazyInitializationException
// outside the persistence context, plus leaks internal FK/entity structure to clients).
@Data
public class AppointmentResponseDto {
    private Long id;
    private LocalDateTime appointmentTime;
    private String reason;
    private DoctorResponseDto doctor;
    // patient is commented out deliberately: this DTO is used from doctor-facing endpoints
    // where the patient is already known/contextual, so echoing it back would be redundant.
//    private PatientResponseDto patient;
}
