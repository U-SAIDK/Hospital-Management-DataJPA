package com.codingshuttle.youtube.hospitalManagement.dto;

import com.codingshuttle.youtube.hospitalManagement.entity.type.BloodGroupType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

// Not an @Entity despite the name — this is the target type of a JPQL constructor-expression
// projection (see PatientRepository#countEachBloodGroupType: "SELECT new ...(p.bloodGroup,
// Count(p))"). For Hibernate to instantiate it directly from the query result row, the
// @AllArgsConstructor's parameter order/types must exactly match the SELECT new(...) argument
// list, and the query must reference this class by its fully-qualified name (as it does).
// Renaming fields, reordering the constructor, or changing types here would silently break
// that JPQL string since it isn't type-checked at compile time.
@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class BloodGroupCountResponseEntity {

    private BloodGroupType bloodGroupType;
    private Long count;
}
