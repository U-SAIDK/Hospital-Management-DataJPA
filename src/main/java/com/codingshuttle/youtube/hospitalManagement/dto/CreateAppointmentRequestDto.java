package com.codingshuttle.youtube.hospitalManagement.dto;

import lombok.Data;

import java.time.LocalDateTime;

// Inbound request shape: carries plain ids (doctorId/patientId), not entity references — the
// service layer resolves these ids into managed Doctor/Patient entities itself, keeping request
// parsing decoupled from persistence/association-loading concerns.
@Data
public class CreateAppointmentRequestDto {
    private Long doctorId;
    private Long patientId;
    private LocalDateTime appointmentTime;
    private String reason;
}
