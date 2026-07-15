package com.codingshuttle.youtube.hospitalManagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Department {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    // Unidirectional @OneToOne (no mappedBy on Doctor side) — Department holds a plain FK to a
    // Doctor with no way to navigate back from Doctor to "the department I head."
    @OneToOne
    private Doctor headDoctor;

    // Department is the owning side of the M:N: it defines the join table explicitly, so this is
    // the side whose collection changes actually get written to my_dpt_doctors. Doctor.departments
    // (mappedBy="departments") is the inverse, read-only-in-effect side.
    @ManyToMany
    @JoinTable(
            name = "my_dpt_doctors",
            joinColumns = @JoinColumn(name = "dpt_id"),
            inverseJoinColumns = @JoinColumn(name = "doctor_id")
    )
    private Set<Doctor> doctors = new HashSet<>();

}
