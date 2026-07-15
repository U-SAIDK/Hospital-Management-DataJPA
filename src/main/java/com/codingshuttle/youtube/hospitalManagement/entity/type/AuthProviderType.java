package com.codingshuttle.youtube.hospitalManagement.entity.type;

// Stored via @Enumerated(EnumType.STRING) on User.providerType, not ordinal — so adding/reordering
// providers here never shifts the meaning of already-persisted rows.
public enum AuthProviderType {
    GOOGLE,
    GITHUB,
    FACEBOOK,
    TWITTER,
    EMAIL
}
