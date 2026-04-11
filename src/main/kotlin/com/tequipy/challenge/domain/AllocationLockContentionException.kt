package com.tequipy.challenge.domain

class AllocationLockContentionException(allocationId: java.util.UUID) :
    RuntimeException("All equipment candidates for allocation $allocationId are locked by concurrent transactions")
