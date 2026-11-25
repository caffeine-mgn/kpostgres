package pw.binom.db.kpostgresql.pool

import pw.binom.db.Connection
import pw.binom.db.kpostgresql.PostgresClient
import pw.binom.db.kpostgresql.PostgresConfig

/**
 * Открывает соединение как [Connection].
 *
 * Тонкая обёртка: `PgConnectionAdapter(PostgresClient.connect(config))`.
 * Существует, чтобы не повторять эту цепочку в тестах и в прикладном коде —
 * и чтобы не забыть, что адаптер идёт **поверх** клиента, а не вместо него.
 */
suspend fun openConnection(config: PostgresConfig): Connection =
    PgConnectionAdapter(PostgresClient.connect(config))

/** То же, но с автозакрытием. */
suspend fun <R> withConnection(config: PostgresConfig, block: suspend (Connection) -> R): R =
    openConnection(config).let { conn ->
        try {
            block(conn)
        } finally {
            conn.close()
        }
    }
