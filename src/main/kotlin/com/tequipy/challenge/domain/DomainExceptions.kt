package com.tequipy.challenge.domain

import java.util.UUID

class BadRequestException(message: String) : RuntimeException(message)
class NotFoundException(message: String) : RuntimeException(message)
class ConflictException(message: String) : RuntimeException(message)

class AllocationLockContentionException(allocationId: UUID) :
    RuntimeException("All equipment candidates for allocation $allocationId are locked by concurrent transactions")

