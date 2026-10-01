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

    @Test
    @EnabledIfEnvironmentVariable(named = "PGHOST", matches = ".+")
    fun simpleQuery() {
        val client = makeClient()
        runBlocking {
            try {
                val res = client.query("SELECT 1 AS a, 'hi' AS b, 3.14 AS c")
                assert(res is QueryResult.Rows) { "expected Rows, got ${res::class.simpleName}" }
                res as QueryResult.Rows
                assert(res.columns.size == 3) { "expected 3 cols, got ${res.columns.size}" }
                assert(res.next()) { "expected at least one row" }
                assert(res.getInt(0) == 1) { "a: ${res.getInt(0)}" }
                assert(res.getString(1) == "hi") { "b: ${res.getString(1)}" }
                assert(res.getDouble(2) == 3.14) { "c: ${res.getDouble(2)}" }
                assert(!res.next()) { "expected exactly one row" }
                res.close()
            } finally {
                client.close()
            }
        }
    }

    private fun makeClient(): PostgresClient {
        val host = System.getenv("PGHOST") ?: "127.0.0.1"
        val port = System.getenv("PGPORT")?.toIntOrNull() ?: 5432
        val db = System.getenv("PGDATABASE") ?: "kp_test"
        val user = System.getenv("PGUSER") ?: "kpgtest"
        val password = System.getenv("PGPASSWORD") ?: "kp_secret"
        val config = PostgresConfig.of(host, port, db, user, password)
        return runBlocking { PostgresClient.connect(config) }
    }
}