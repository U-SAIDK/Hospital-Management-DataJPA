package com.codingshuttle.youtube.hospitalManagement.service;

import com.codingshuttle.youtube.hospitalManagement.dto.AppointmentResponseDto;
import com.codingshuttle.youtube.hospitalManagement.dto.CreateAppointmentRequestDto;
import com.codingshuttle.youtube.hospitalManagement.entity.Appointment;
import com.codingshuttle.youtube.hospitalManagement.entity.Doctor;
import com.codingshuttle.youtube.hospitalManagement.entity.Patient;
import com.codingshuttle.youtube.hospitalManagement.repository.AppointmentRepository;
import com.codingshuttle.youtube.hospitalManagement.repository.DoctorRepository;
import com.codingshuttle.youtube.hospitalManagement.repository.PatientRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final ModelMapper modelMapper;

    // @Transactional keeps both Patient and Doctor as managed entities for the whole method so the
    // save below and the in-memory graph updates happen atomically in one persistence context.
    // @Secured is a coarse, role-only check (unlike @PreAuthorize it can't use SpEL/method args) -
    // fine here since any PATIENT may book an appointment, no ownership check is needed.
    @Transactional
    @Secured("ROLE_PATIENT")
    public AppointmentResponseDto createNewAppointment(CreateAppointmentRequestDto createAppointmentRequestDto) {
        Long doctorId = createAppointmentRequestDto.getDoctorId();
        Long patientId = createAppointmentRequestDto.getPatientId();

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new EntityNotFoundException("Patient not found with ID: " + patientId));
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new EntityNotFoundException("Doctor not found with ID: " + doctorId));
        Appointment appointment = Appointment.builder()
                .reason(createAppointmentRequestDto.getReason())
                .appointmentTime(createAppointmentRequestDto.getAppointmentTime())
                .build();

        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        // Only the FK-owning side (appointment.patient/doctor) actually controls what gets persisted;
        // this line just keeps Patient's in-memory collection in sync so re-reading it in the same
        // persistence context (without hitting the DB again) reflects the new appointment.
        patient.getAppointments().add(appointment); // to maintain consistency

        appointment = appointmentRepository.save(appointment);
        return modelMapper.map(appointment, AppointmentResponseDto.class);
    }

    // SpEL here reaches past the role check into the actual argument (#doctorId) and the authenticated
    // principal, allowing a doctor to reassign only their OWN appointments unless they hold the
    // coarser 'appointment:write' authority (e.g. admin) - this is finer-grained than URL-level
    // security in WebSecurityConfig can express.
    @Transactional
    @PreAuthorize("hasAuthority('appointment:write') or #doctorId == authentication.principal.id")
    public Appointment reAssignAppointmentToAnotherDoctor(Long appointmentId, Long doctorId) {
        Appointment appointment = appointmentRepository.findById(appointmentId).orElseThrow();
        Doctor doctor = doctorRepository.findById(doctorId).orElseThrow();

        // No explicit save() call here. Because this method is @Transactional and `appointment` was
        // loaded via findById (so it's a managed entity attached to the persistence context), Hibernate
        // compares its current state to the snapshot taken at load time when the transaction flushes/
        // commits, notices doctor changed, and issues the UPDATE itself - this is dirty checking.
        appointment.setDoctor(doctor); // this will automatically call the update, because it is dirty

        // Same bidirectional-consistency reasoning as createNewAppointment: this does not affect what
        // gets written to the DB (the FK lives on Appointment), it only keeps Doctor's in-memory
        // collection correct for the rest of this persistence context's lifetime.
        doctor.getAppointments().add(appointment); // just for bidirectional consistency

        return appointment;
    }

    // Combines a coarse role check with the same self-service ownership pattern as above - a DOCTOR
    // can only list their own appointments, while an ADMIN can list any doctor's.
    @PreAuthorize("hasRole('ADMIN') OR (hasRole('DOCTOR') AND #doctorId == authentication.principal.id)")
    public List<AppointmentResponseDto> getAllAppointmentsOfDoctor(Long doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId).orElseThrow();

        // Entities never leave the service layer directly - mapping to AppointmentResponseDto here
        // keeps the HTTP contract stable even if the entity graph changes, and avoids serializing
        // lazy associations (e.g. Doctor.appointments) straight into JSON.
        return doctor.getAppointments()
                .stream()
                .map(appointment -> modelMapper.map(appointment, AppointmentResponseDto.class))
                .collect(Collectors.toList());
    }
}
