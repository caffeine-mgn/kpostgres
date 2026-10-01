package pw.binom.db.kpostgresql.internal

internal object MessageTag {
    const val PARSE: Byte = 'P'.code.toByte()
    const val BIND: Byte = 'B'.code.toByte()
    const val DESCRIBE: Byte = 'D'.code.toByte()
    const val EXECUTE: Byte = 'E'.code.toByte()
    const val SYNC: Byte = 'S'.code.toByte()
    const val FLUSH: Byte = 'H'.code.toByte()
    const val CLOSE: Byte = 'C'.code.toByte()
    const val QUERY: Byte = 'Q'.code.toByte()
    const val PASSWORD: Byte = 'p'.code.toByte()
    const val TERMINATE: Byte = 'X'.code.toByte()
    const val COPY_DATA: Byte = 'd'.code.toByte()
    const val COPY_DONE: Byte = 'c'.code.toByte()
    const val COPY_FAIL: Byte = 'f'.code.toByte()
    const val FUNCTION_CALL: Byte = 'F'.code.toByte()

    const val AUTHENTICATION: Byte = 'R'.code.toByte()
    const val BACKEND_KEY_DATA: Byte = 'K'.code.toByte()
    const val BIND_COMPLETE: Byte = '2'.code.toByte()
    const val CLOSE_COMPLETE: Byte = '3'.code.toByte()
    const val COMMAND_COMPLETE: Byte = 'C'.code.toByte()
    const val COPY_IN_RESPONSE: Byte = 'G'.code.toByte()
    const val COPY_OUT_RESPONSE: Byte = 'H'.code.toByte()
    const val COPY_BOTH_RESPONSE: Byte = 'W'.code.toByte()
    const val DATA_ROW: Byte = 'D'.code.toByte()
    const val EMPTY_QUERY_RESPONSE: Byte = 'I'.code.toByte()
    const val ERROR_RESPONSE: Byte = 'E'.code.toByte()
    const val FUNCTION_CALL_RESPONSE: Byte = 'V'.code.toByte()
    const val NEGOTIATE_PROTOCOL_VERSION: Byte = 'v'.code.toByte()
    const val NO_DATA: Byte = 'n'.code.toByte()
    const val NOTICE_RESPONSE: Byte = 'N'.code.toByte()
    const val NOTIFICATION_RESPONSE: Byte = 'A'.code.toByte()
    const val PARAMETER_DESCRIPTION: Byte = 't'.code.toByte()
    const val PARAMETER_STATUS: Byte = 'S'.code.toByte()
    const val PARSE_COMPLETE: Byte = '1'.code.toByte()
    const val PORTAL_SUSPENDED: Byte = 's'.code.toByte()
    const val READY_FOR_QUERY: Byte = 'Z'.code.toByte()
    const val ROW_DESCRIPTION: Byte = 'T'.code.toByte()
}

internal object AuthType {
    const val OK: Int = 0
    const val CLEARTEXT_PASSWORD: Int = 3
    const val MD5_PASSWORD: Int = 5
    const val SASL: Int = 10
    const val SASL_CONTINUE: Int = 11
    const val SASL_FINAL: Int = 12
}

internal object TransactionStatus {
    const val IDLE: Byte = 'I'.code.toByte()
    const val IN_TRANSACTION: Byte = 'T'.code.toByte()
    const val IN_FAILED_TRANSACTION: Byte = 'E'.code.toByte()
}

internal object ProtocolVersion {
    const val VALUE: Int = (3 shl 16) or 0
}