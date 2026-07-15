package com.codingshuttle.youtube.hospitalManagement.dto;

import com.codingshuttle.youtube.hospitalManagement.entity.type.BloodGroupType;
import lombok.Data;

import java.time.LocalDate;

// API-boundary shape for Patient: deliberately omits the insurance and appointments
// associations (and the shared User/security fields) — returning the Patient entity directly
// would risk serializing its EAGER appointments collection and the owned Insurance every time,
// even for endpoints that only need basic demographic info.
@Data
public class PatientResponseDto {
    private Long id;
    private String name;
    private String gender;
    private LocalDate birthDate;
    private BloodGroupType bloodGroup;
}
