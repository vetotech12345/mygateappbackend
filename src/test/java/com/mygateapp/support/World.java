package com.mygateapp.support;

import java.util.UUID;

/**
 * A complete society created through the public admin APIs: one tower "A" with flats 101-104 and 201-204, a main
 * gate, an on-duty guard with all gate permissions, an owner in A-101, an owner in A-102 and a society admin.
 */
public record World(
        UUID societyId,
        String adminToken,
        UUID towerId,
        UUID gateId,
        UUID flat101,
        UUID flat102,
        UUID flat201,
        UUID guardId,
        String guardMobile,
        String guardToken,
        String resident101Mobile,
        String resident101Token,
        UUID resident101UserId,
        String resident102Mobile,
        String resident102Token) {
}
