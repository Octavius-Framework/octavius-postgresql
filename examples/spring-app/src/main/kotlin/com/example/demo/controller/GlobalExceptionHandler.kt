package com.example.demo.controller

import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.exception.ConstraintViolationExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.exception.TransactionStateException
import io.github.octaviusframework.driver.exception.TransactionStateExceptionReason
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<Map<String, String>> {
        if (ex.reason == ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf("error" to "A user with this name already exists.", "details" to ex.dbMessage)
            )
        }
        return handleOctaviusException(ex)
    }

    @ExceptionHandler(TransactionStateException::class)
    fun handleTransactionState(ex: TransactionStateException): ResponseEntity<Map<String, String>> {
        if (ex.reason == TransactionStateExceptionReason.READ_ONLY_TRANSACTION) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                mapOf("error" to "Cannot modify data in a read-only transaction.", "details" to ex.dbMessage)
            )
        }
        return handleOctaviusException(ex)
    }

    @ExceptionHandler(OctaviusException::class)
    fun handleOctaviusException(ex: OctaviusException): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            mapOf("error" to "An unexpected database error occurred.", "details" to (ex.message ?: ""))
        )
    }
}
