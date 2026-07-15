package com.codingshuttle.youtube.hospitalManagement.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Insurance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String policyNumber;

    @Column(nullable = false, length = 100)
    private String provider;

    @Column(nullable = false)
    private LocalDate validUntil;

    // updatable=false ensures Hibernate only ever sets this on INSERT — subsequent updates to the
    // Insurance row leave the original creation time untouched.
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Inverse side: no FK column lives here. Patient.insurance (with @JoinColumn) is the owner,
    // so nulling this field from the Insurance side has no effect on the database.
    @OneToOne(mappedBy = "insurance") // inverse side
    private Patient patient;

}
