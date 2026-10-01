package pw.binom.db.kpostgresql

import org.testcontainers.containers.PostgreSQLContainer

abstract class AbstractPgContainer {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> by lazy {
            PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("kp_it")
                withUsername("kpit")
                withPassword("kpit_secret")
                withCommand("postgres", "-c", "password_encryption=scram-sha-256")
                start()
            }
        }

        @JvmStatic
        fun pgConfig(): PostgresConfig {
            val c: PostgreSQLContainer<*> = postgres
            return PostgresConfig.of(
                c.host,
                c.firstMappedPort,
                c.databaseName,
                c.username,
                c.password,
            )
        }

        @JvmStatic
        fun startOnce(): PostgreSQLContainer<*> {
            postgres.start()
            return postgres
        }
    }
}