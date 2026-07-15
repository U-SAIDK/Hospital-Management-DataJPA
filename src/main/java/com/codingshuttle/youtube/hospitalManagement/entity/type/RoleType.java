package com.codingshuttle.youtube.hospitalManagement.entity.type;

// The coarse role vocabulary stored per-User (User.roles). RolePermissionMapping is the single
// source of truth mapping each of these to a set of fine-grained PermissionTypes.
public enum RoleType {
    ADMIN,
    DOCTOR,
    PATIENT
}
