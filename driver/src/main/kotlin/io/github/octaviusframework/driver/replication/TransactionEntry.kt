package io.github.octaviusframework.driver.replication

/** What a replicated transaction holds, in its order: a [Change] it made, or a [LogicalMessage] it wrote. */
sealed interface TransactionEntry
