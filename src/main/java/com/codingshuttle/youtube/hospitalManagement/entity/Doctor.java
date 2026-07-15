package com.codingshuttle.youtube.hospitalManagement.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Doctor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @MapsId makes Doctor's PK the same value as the linked User's PK (shared primary key
    // pattern) — no separate doctor_id sequence, and a Doctor row can only exist for a User row.
    @OneToOne
    @MapsId
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String specialization;

    @Column(unique = true, length = 100)
    private String email;

    // Doctor is the inverse (non-owning) side of the M:N with Department; the join table itself
    // is only defined on Department's @JoinTable, so mappedBy just points back to that field.
    @ManyToMany(mappedBy = "doctors")
    private Set<Department> departments = new HashSet<>();

    // Default fetch for @OneToMany is LAZY — deliberately left as-is here, unlike
    // Patient.appointments (EAGER), as a teaching contrast between the two fetch strategies.
    @OneToMany(mappedBy = "doctor")
    private List<Appointment> appointments = new ArrayList<>();

}
