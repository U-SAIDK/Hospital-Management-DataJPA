package com.codingshuttle.youtube.hospitalManagement.entity.type;

// Also mapped with EnumType.STRING at the Patient.bloodGroup field — same reasoning as
// AuthProviderType: protects stored data against enum reordering, and reads clearly in the DB.
public enum BloodGroupType {
    A_POSITIVE,
    A_NEGATIVE,
    B_POSITIVE,
    B_NEGATIVE,
    AB_POSITIVE,
    AB_NEGATIVE,
    O_POSITIVE,
    O_NEGATIVE
}
