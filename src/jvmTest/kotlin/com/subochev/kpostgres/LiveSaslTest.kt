package com.subochev.kpostgres

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class LiveSaslTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "PGHOST", matches = ".+")
    fun connectWithSCRAM() {
        val host = System.getenv("PGHOST") ?: "127.0.0.1"
        val port = System.getenv("PGPORT")?.toIntOrNull() ?: 5432
        val db = System.getenv("PGDATABASE") ?: "kp_test"
        val user = System.getenv("PGUSER") ?: "kpgtest"
        val password = System.getenv("PGPASSWORD") ?: "kp_secret"

        val config = PostgresConfig.of(host, port, db, user, password)

        runBlocking {
            val client = PostgresClient.connect(config)
            println("Connected OK to ${host}:${port}/${db} as ${user}")
            client.close()
        }
    }
}