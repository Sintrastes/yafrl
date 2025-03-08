import io.github.sintrastes.yafrl.*
import io.github.sintrastes.yafrl.internal.newTimeline
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals

class StateUpdatingTests {
    @Test
    fun `Build node`() {
        runBlocking(newTimeline()) {
            val node = mutableStateOf(0)

            assert(node.current() == 0)
        }
    }

    @Test
    fun `Build map node`() {
        runBlocking(newTimeline()) {
            val node = mutableStateOf(0)

            val mapped = node
                .map { it + 2 }

            assert(mapped.current() == 2)
        }
    }

    @Test
    fun `Map node updates immediately`() {
        runBlocking(newTimeline()) {
            val node = mutableStateOf(0)

            val mapped = node
                .map { it + 2 }

            node.setTo(3)

            assertEquals(5, mapped.current())
        }
    }

    @Test
    fun `Map does not update unless queried`() {
        var mapEvaluated = false

        runBlocking(newTimeline()) {
            val node = mutableStateOf(0)

            node.map {
                mapEvaluated = true
                it + 2
            }

            node.setTo(3)

            assert(!mapEvaluated)
        }
    }

    @Test
    fun `Map updates if listened to`() {
        var mapEvaluated = false

        runBlocking(newTimeline()) {
            val node = mutableStateOf(0)

            val mapped = node.map {
                mapEvaluated = true
                it + 2
            }

            mapped.collect { value ->
                println("Collecting $value")
            }

            node.setTo(3)

            assert(mapEvaluated)
        }
    }
}