package nl.uva.nepa

import java.util.concurrent.Future

interface Cancellable {
    fun cancel()
}

fun cancellable(onCancel: () -> Unit) = object : Cancellable {
    override fun cancel() {
        onCancel()
    }
}

operator fun Cancellable?.plus(cancellables: Collection<Cancellable>) = object : Cancellable {
    override fun cancel() {
        cancellables.forEach { it.cancel() }
        this@plus?.cancel()
    }
}

fun <V>Future<V>.asCancellable(mayInterruptIfRunning: Boolean) = object : Cancellable {
    override fun cancel() {
        cancel(mayInterruptIfRunning)
    }
}

