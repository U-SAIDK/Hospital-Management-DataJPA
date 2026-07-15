package com.codingshuttle.youtube.hospitalManagement.entity;

import com.codingshuttle.youtube.hospitalManagement.entity.type.BloodGroupType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@ToString
@Getter
@Setter
@Table(
        name = "patient",
        uniqueConstraints = {
                // Notice: the email unique constraint was tried and then commented out — an example
                // of an evolving schema decision left in place rather than cleaned up. The @Column
                // below still enforces uniqueness on email regardless, so this line is currently redundant.
//                @UniqueConstraint(name = "unique_patient_email", columnNames = {"email"}),
                @UniqueConstraint(name = "unique_patient_name_birthdate", columnNames = {"name", "birthDate"})
        },
        indexes = {
                // Speeds up the derived/JPQL queries that filter or range-scan on birthDate
                // (e.g. findByBirthDateBetween, findByBornAfterDate in PatientRepository).
                @Index(name = "idx_patient_birth_date", columnList = "birthDate")
        }
)
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String name;

    //    @ToString.Exclude
    private LocalDate birthDate;

    @Column(unique = true, nullable = false)
    private String email;

    private String gender;

    // Same shared-PK pattern as Doctor: a User row backs either a Patient or a Doctor row via
    // this @MapsId, never both — the id column here IS the User's id, not an independent sequence.
    @OneToOne
    @MapsId
    private User user;

    // @CreationTimestamp + updatable=false: Hibernate stamps this once on INSERT and then never
    // touches it again, even if the entity is later updated — a reliable "record created at" audit field.
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private BloodGroupType bloodGroup;

    // Patient is the owning side (holds the FK column) and fully owns Insurance's lifecycle:
    // CascadeType.ALL propagates save/delete, and orphanRemoval=true means setting this field to
    // null (not just deleting the Patient) is enough to delete the Insurance row on flush
    // (see InsuranceService.disaccociateInsuranceFromPatient).
    @OneToOne(cascade = {CascadeType.ALL}, orphanRemoval = true)
    @JoinColumn(name = "patient_insurance_id") // owning side
    private Insurance insurance;

    // cascade=REMOVE + orphanRemoval: deleting a Patient deletes all their Appointments too.
    // fetch=EAGER is a deliberate contrast with Doctor.appointments (LAZY) for teaching the two
    // fetch strategies side by side — not a universally "correct" choice for a to-many association.
    @OneToMany(mappedBy = "patient", cascade = {CascadeType.REMOVE}, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Appointment> appointments = new ArrayList<>();
}