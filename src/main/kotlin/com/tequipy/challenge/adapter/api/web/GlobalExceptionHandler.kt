package com.tequipy.challenge.adapter.api.web

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = KotlinLogging.logger {}

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException) = errorResponse(HttpStatus.NOT_FOUND, ex)

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException) = errorResponse(HttpStatus.BAD_REQUEST, ex)

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException) = errorResponse(HttpStatus.CONFLICT, ex)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<Map<String, String>> {
        val message = ex.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "Validation failed"
        logger.warn { "Validation failed: $message" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to message))
    }

    private fun errorResponse(status: HttpStatus, ex: RuntimeException): ResponseEntity<Map<String, String>> {
        logger.warn { "${status.reasonPhrase}: ${ex.message}" }
        return ResponseEntity.status(status).body(mapOf("error" to (ex.message ?: status.reasonPhrase)))
    }
}
