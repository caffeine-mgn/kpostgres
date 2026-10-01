package pw.binom.db.kpostgresql

public interface SuspendCloseable {
    public suspend fun close()
}

public suspend fun <T : SuspendCloseable, R> T.useSuspending(block: suspend (T) -> R): R {
    var thrown: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        thrown = e
        throw e
    } finally {
        try {
            close()
        } catch (closeException: Throwable) {
            if (thrown == null) throw closeException
            else thrown.addSuppressed(closeException)
        }
    }
}