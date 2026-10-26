package com.example.manager

import android.util.Log

/**
 * Centralized security logging manager.
 * Replaces all printStackTrace() and silent exception handlers.
 *
 * AUDIT FIX: CWE-391 - Unchecked Error Condition (33 instances)
 * Severity: CRITICAL
 * Ensures all security events are logged for audit trail
 */
object SecurityLogger {
    private const val TAG = "SecurityAudit"
    
    enum class EventType {
        PERMISSION_DENIED,
        COOKIE_CLEAR_FAILED,
        DATABASE_ERROR,
        ENCRYPTION_FAILED,
        NETWORK_ERROR,
        WEBVIEW_ERROR,
        FILE_OPERATION_FAILED,
        SERVICE_ERROR,
        AUTHENTICATION_FAILED,
        VALIDATION_ERROR,
        UNKNOWN_ERROR
    }
    
    data class SecurityEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val type: EventType,
        val component: String,
        val message: String,
        val exception: Exception? = null,
        val severity: String = "ERROR" // ERROR, WARNING, INFO
    )
    
    private val securityEventLog = mutableListOf<SecurityEvent>()
    private const val MAX_LOG_SIZE = 1000
    
    /**
     * Log security event with full details and severity
     */
    fun logSecurityEvent(
        type: EventType,
        component: String,
        message: String,
        exception: Exception? = null,
        severity: String = "ERROR"
    ) {
        val event = SecurityEvent(
            type = type,
            component = component,
            message = message,
            exception = exception,
            severity = severity
        )
        
        // Add to internal log (bounded to prevent memory issues)
        synchronized(securityEventLog) {
            securityEventLog.add(event)
            if (securityEventLog.size > MAX_LOG_SIZE) {
                securityEventLog.removeAt(0)
            }
        }
        
        // Log to Android logger
        val fullMessage = "[$component] $message"
        when (severity) {
            "ERROR" -> {
                Log.e(TAG, fullMessage, exception)
            }
            "WARNING" -> {
                Log.w(TAG, fullMessage, exception)
            }
            "INFO" -> {
                Log.i(TAG, fullMessage)
            }
            else -> {
                Log.d(TAG, fullMessage)
            }
        }
    }
    
    fun logPermissionDenied(component: String, permission: String) {
        logSecurityEvent(
            type = EventType.PERMISSION_DENIED,
            component = component,
            message = "Permission denied: $permission",
            severity = "WARNING"
        )
    }
    
    fun logCookieClearFailed(component: String, exception: Exception) {
        logSecurityEvent(
            type = EventType.COOKIE_CLEAR_FAILED,
            component = component,
            message = "Failed to clear cookies",
            exception = exception
        )
    }
    
    fun logDatabaseError(component: String, operation: String, exception: Exception) {
        logSecurityEvent(
            type = EventType.DATABASE_ERROR,
            component = component,
            message = "Database operation failed: $operation",
            exception = exception
        )
    }
    
    fun logEncryptionFailed(component: String, reason: String, exception: Exception) {
        logSecurityEvent(
            type = EventType.ENCRYPTION_FAILED,
            component = component,
            message = "Encryption failed: $reason",
            exception = exception
        )
    }
    
    fun logNetworkError(component: String, url: String, exception: Exception) {
        logSecurityEvent(
            type = EventType.NETWORK_ERROR,
            component = component,
            message = "Network error accessing: $url",
            exception = exception
        )
    }
    
    fun logWebViewError(component: String, message: String, exception: Exception) {
        logSecurityEvent(
            type = EventType.WEBVIEW_ERROR,
            component = component,
            message = message,
            exception = exception
        )
    }
    
    fun logFileOperationFailed(component: String, operation: String, exception: Exception) {
        logSecurityEvent(
            type = EventType.FILE_OPERATION_FAILED,
            component = component,
            message = "File operation failed: $operation",
            exception = exception
        )
    }
    
    fun logServiceError(component: String, serviceName: String, exception: Exception) {
        logSecurityEvent(
            type = EventType.SERVICE_ERROR,
            component = component,
            message = "Service error: $serviceName",
            exception = exception
        )
    }
    
    fun logValidationError(component: String, message: String) {
        logSecurityEvent(
            type = EventType.VALIDATION_ERROR,
            component = component,
            message = message,
            severity = "WARNING"
        )
    }
    
    fun getSecurityEventLog(): List<SecurityEvent> {
        synchronized(securityEventLog) {
            return securityEventLog.toList()
        }
    }
    
    fun clearLog() {
        synchronized(securityEventLog) {
            securityEventLog.clear()
        }
    }
    
    fun exportLog(): String {
        synchronized(securityEventLog) {
            return securityEventLog.joinToString("\n") { event ->
                "${event.timestamp} [${event.type}] ${event.component}: ${event.message}"
            }
        }
    }
}
