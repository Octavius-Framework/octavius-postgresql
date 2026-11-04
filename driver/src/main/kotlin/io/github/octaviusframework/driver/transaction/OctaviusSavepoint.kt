package io.github.octaviusframework.driver.transaction

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.identifier.quoteAsPgIdentifier
import java.sql.Savepoint

/** A savepoint, either numbered by the driver or named. */
interface OctaviusSavepoint {
    /**
     * The number of an unnamed savepoint.
     *
     * @throws InvalidOperationException `INVALID_SAVEPOINT` if the savepoint is named.
     */
    fun getSavepointId(): Int

    /**
     * The name of a named savepoint.
     *
     * @throws InvalidOperationException `INVALID_SAVEPOINT` if the savepoint is unnamed.
     */
    fun getSavepointName(): String
}

/** The [OctaviusSavepoint] that is also a JDBC [Savepoint]. */
internal class OctaviusSavepointImpl : OctaviusSavepoint, Savepoint {
    private val savepointId: Int
    private val savepointName: String?

    val pgName: String

    constructor(id: Int) {
        this.savepointId = id
        this.savepointName = null
        this.pgName = "octavius_savepoint_$id"
    }

    constructor(name: String) {
        this.savepointId = -1
        this.savepointName = name
        this.pgName = name.quoteAsPgIdentifier()
    }

    override fun getSavepointId(): Int {
        if (savepointName != null) {
            throw InvalidOperationException(InvalidOperationExceptionReason.INVALID_SAVEPOINT, "Savepoint is named")
        }
        return savepointId
    }

    override fun getSavepointName(): String {
        return savepointName ?: throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_SAVEPOINT,
            "Savepoint is un-named"
        )
    }
}
