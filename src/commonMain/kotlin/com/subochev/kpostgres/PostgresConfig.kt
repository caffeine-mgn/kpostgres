package com.subochev.kpostgres

public data class PostgresConfig(
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String,
    val user: String,
    val password: String,
    val applicationName: String = "kpostgres",
    val connectTimeoutMs: Long = 10_000L,
    val readTimeoutMs: Long = 30_000L,
) {
    public companion object {
        public fun of(
            host: String,
            port: Int = 5432,
            database: String,
            user: String,
            password: String,
            applicationName: String = "kpostgres",
        ): PostgresConfig = PostgresConfig(
            host = host,
            port = port,
            database = database,
            user = user,
            password = password,
            applicationName = applicationName,
        )
    }
}