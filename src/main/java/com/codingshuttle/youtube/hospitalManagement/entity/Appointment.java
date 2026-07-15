package com.codingshuttle.youtube.hospitalManagement.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Appointment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime appointmentTime;

    @Column(length = 500)
    private String reason;

    // No explicit fetch here: @ManyToOne defaults to EAGER, so patient loads immediately with the
    // Appointment (contrast with doctor below, which opts into LAZY).
    @ManyToOne
    @ToString.Exclude // avoids pulling in Patient's own toString (and its EAGER appointments list) recursively
    @JoinColumn(name = "patient_id", nullable = false) // patient is required and not nullable
    private Patient patient;

    // Explicitly LAZY: doctor is loaded as a Hibernate proxy until actually accessed, avoiding an
    // extra join/query whenever an Appointment is fetched but the doctor's own fields aren't needed.
    @ManyToOne(fetch = FetchType.LAZY)
    @ToString.Exclude // prevents forcing the lazy proxy to initialize just to build a toString, and avoids recursion
    @JoinColumn(nullable = false)
    private Doctor doctor;
}
