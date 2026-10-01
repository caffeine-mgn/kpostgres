package pw.binom.db.kpostgresql

public open class PostgresException(
    message: String,
    public val sqlState: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public class PostgresConnectionClosedException(
    message: String = "Postgres connection is closed",
    cause: Throwable? = null,
) : PostgresException(message = message, cause = cause)

public class PostgresProtocolException(
    message: String,
    cause: Throwable? = null,
) : PostgresException(message, cause = cause)