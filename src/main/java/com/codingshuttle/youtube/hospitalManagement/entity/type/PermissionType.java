package com.codingshuttle.youtube.hospitalManagement.entity.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// @RequiredArgsConstructor generates the constructor for the final `permission` field per-constant
// (the "patient:read" style string), and @Getter exposes it — this string, not the enum name, is
// what ends up as a Spring Security authority (see RolePermissionMapping / User.getAuthorities()).
@Getter
@RequiredArgsConstructor
public enum PermissionType {
    PATIENT_READ("patient:read"),
    PATIENT_WRITE("patient:write"),
    APPOINTMENT_READ("appointment:read"),
    APPOINTMENT_WRITE("appointment:write"),
    APPOINTMENT_DELETE("appointment:delete"),
    USER_MANAGE("user:manage"), // For admin tasks
    REPORT_VIEW("report:view");

    private final String permission;
}
