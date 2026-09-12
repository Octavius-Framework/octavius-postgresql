package com.example.demo.controller

import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.exception.ConstraintViolationExceptionReason
import io.github.octaviusframework.driver.exception.TransactionStateException
import io.github.octaviusframework.driver.exception.TransactionStateExceptionReason
import io.github.octaviusframework.driver.spring.exception.OctaviusDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(OctaviusDataAccessException::class)
    fun handleOctaviusException(ex: OctaviusDataAccessException): ResponseEntity<Map<String, String>> {
        val rootEx = ex.octaviusException

        if (rootEx is ConstraintViolationException && rootEx.reason == ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf("error" to "A user with this name already exists.", "details" to rootEx.dbMessage)
            )
        }

        if (rootEx is TransactionStateException && rootEx.reason == TransactionStateExceptionReason.READ_ONLY_TRANSACTION) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                mapOf("error" to "Cannot modify data in a read-only transaction.", "details" to rootEx.dbMessage)
            )
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            mapOf("error" to "An unexpected database error occurred.", "details" to (ex.message ?: ""))
        )
    }
}
