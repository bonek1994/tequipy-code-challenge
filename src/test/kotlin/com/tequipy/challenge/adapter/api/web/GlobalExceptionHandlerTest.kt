package com.tequipy.challenge.adapter.api.web

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.core.MethodParameter

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `handleNotFound returns 404 with error message`() {
        val response = handler.handleNotFound(NotFoundException("Equipment not found"))
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("Equipment not found", response.body!!["error"])
    }

    @Test
    fun `handleNotFound returns default message when null`() {
        val ex = NotFoundException("Not found")
        val response = handler.handleNotFound(ex)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("Not found", response.body!!["error"])
    }

    @Test
    fun `handleBadRequest returns 400 with error message`() {
        val response = handler.handleBadRequest(BadRequestException("Brand must not be blank"))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Brand must not be blank", response.body!!["error"])
    }

    @Test
    fun `handleConflict returns 409 with error message`() {
        val response = handler.handleConflict(ConflictException("Allocation is not in ALLOCATED state"))
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("Allocation is not in ALLOCATED state", response.body!!["error"])
    }

    @Test
    fun `handleValidation returns 400 with first field error message`() {
        val bindingResult = BeanPropertyBindingResult(Any(), "request")
        bindingResult.addError(FieldError("request", "brand", "must not be blank"))

        val method = this::class.java.methods.first()
        val methodParameter = MethodParameter(method, -1)
        val ex = MethodArgumentNotValidException(methodParameter, bindingResult)

        val response = handler.handleValidation(ex)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("must not be blank", response.body!!["error"])
    }

    @Test
    fun `handleValidation returns default message when no field errors`() {
        val bindingResult = BeanPropertyBindingResult(Any(), "request")
        val method = this::class.java.methods.first()
        val methodParameter = MethodParameter(method, -1)
        val ex = MethodArgumentNotValidException(methodParameter, bindingResult)

        val response = handler.handleValidation(ex)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Validation failed", response.body!!["error"])
    }
}

