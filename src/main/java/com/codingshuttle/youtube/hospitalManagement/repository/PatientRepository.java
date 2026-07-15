package com.codingshuttle.youtube.hospitalManagement.repository;

import com.codingshuttle.youtube.hospitalManagement.dto.BloodGroupCountResponseEntity;
import com.codingshuttle.youtube.hospitalManagement.entity.Patient;
import com.codingshuttle.youtube.hospitalManagement.entity.type.BloodGroupType;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    // Derived query method: Spring Data parses "findByName" from the method signature alone
    // (no @Query, no runtime reflection per call) into "WHERE name = ?". Simplest mechanism —
    // reach for it first whenever the filter maps directly onto one or two entity fields.
    Patient findByName(String name);

    // Derived query with an implicit OR baked into the method name itself
    // ("...BirthDateOrEmail") — still no JPQL needed since Spring Data's naming grammar
    // covers boolean combinators directly.
    List<Patient> findByBirthDateOrEmail(LocalDate birthDate, String email);

    // Derived query using the "Between" keyword — Spring Data maps this to a BETWEEN clause;
    // still purely name-driven, no @Query required.
    List<Patient> findByBirthDateBetween(LocalDate startDate, LocalDate endDate);

    // Derived query combining "Containing" (LIKE %query%) with "OrderBy...Desc" — shows how far
    // the naming convention can go before it becomes unreadable and JPQL becomes the better choice.
    List<Patient> findByNameContainingOrderByIdDesc(String query);

    // JPQL (@Query): needed here because the filter is on an enum-typed field (BloodGroupType)
    // and JPQL operates on entity/field names, not table/column names — same expressive power as
    // a derived method but written explicitly, useful once queries get more complex than the
    // naming convention can cleanly express. ?1 is positional binding (matches the ordinal
    // parameter position rather than a name).
    @Query("SELECT p FROM Patient p where p.bloodGroup = ?1")
    List<Patient> findByBloodGroup(@Param("bloodGroup") BloodGroupType bloodGroup);

    // JPQL with named parameter binding (:birthDate) instead of positional (?1) — preferred in
    // real projects because it's resilient to parameter reordering and self-documenting.
    @Query("select p from Patient p where p.birthDate > :birthDate")
    List<Patient> findByBornAfterDate(@Param("birthDate") LocalDate birthDate);

    // JPQL constructor-expression / DTO projection: "SELECT new <fully-qualified-DTO>(...)"
    // tells Hibernate to build BloodGroupCountResponseEntity instances directly in the query
    // instead of loading full Patient entities and mapping them afterwards — cheaper (only the
    // grouped columns come back over the wire) and the aggregate (Count(p)) has no natural home
    // on the Patient entity itself. Requires the DTO to have an exact-matching constructor and be
    // referenced by its fully-qualified name (see BloodGroupCountResponseEntity).
    // The commented-out `List<Object[]>` line below is the "before" version of this same
    // query — Object[] projections work but lose type safety/readability; left in place as a
    // teaching contrast, not dead code to remove.
    @Query("select new com.codingshuttle.youtube.hospitalManagement.dto.BloodGroupCountResponseEntity(p.bloodGroup," +
            " Count(p)) from Patient p group by p.bloodGroup")
//    List<Object[]> countEachBloodGroupType();
    List<BloodGroupCountResponseEntity> countEachBloodGroupType();

    // Native SQL (nativeQuery = true): escapes JPQL entirely and runs raw SQL against the table
    // ("patient", not the entity name) — useful for DB-specific syntax or when JPQL can't express
    // something. Returning Page<Patient> + accepting Pageable still works with native queries;
    // Spring Data appends LIMIT/OFFSET (and, for Page, issues a second COUNT query) even though
    // the base query is raw SQL. Called from PatientService.getAllPatients with a PageRequest.
    @Query(value = "select * from patient", nativeQuery = true)
    Page<Patient> findAllPatients(Pageable pageable);

    // @Modifying + @Transactional on a JPQL UPDATE: this issues a single bulk UPDATE statement
    // directly against the DB, completely bypassing the persistence context — no entities are
    // loaded, so Hibernate's dirty-checking/entity listeners never run for this write. @Modifying
    // is required to tell Spring Data this @Query is a DML statement (not a SELECT); @Transactional
    // is required because bulk updates must run inside a transaction. Trap: if a Patient with this
    // id is already managed/cached in the current persistence context, it will NOT reflect this
    // change until reloaded/refreshed — contrast with the dirty-checking example in
    // AppointmentService, which goes through the persistence context on purpose.
    @Transactional
    @Modifying
    @Query("UPDATE Patient p SET p.name = :name where p.id = :id")
    int updateNameWithId(@Param("name") String name, @Param("id") Long id);


    // JOIN FETCH: forces Hibernate to load Patient and its appointments association in ONE SQL
    // query (a single JOIN) instead of a separate query per patient — the classic fix for N+1
    // when iterating a list of patients and accessing .getAppointments() on each. LEFT JOIN FETCH
    // (not INNER) so patients with zero appointments are still included. The commented-out variant
    // below shows how a second FETCH (a.doctor) could flatten a deeper association chain into the
    // same single query — left in as a teaching example, not something to uncomment casually
    // (each extra collection fetch-join can multiply row counts / trigger MultipleBagFetchException).
    //    @Query("SELECT p FROM Patient p LEFT JOIN FETCH p.appointments a LEFT JOIN FETCH a.doctor")
    @Query("SELECT p FROM Patient p LEFT JOIN FETCH p.appointments")
    List<Patient> findAllPatientWithAppointment();

}
