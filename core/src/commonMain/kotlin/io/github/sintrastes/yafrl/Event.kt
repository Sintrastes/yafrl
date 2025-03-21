package io.github.sintrastes.yafrl

import io.github.sintrastes.yafrl.annotations.FragileYafrlAPI
import io.github.sintrastes.yafrl.internal.Node
import io.github.sintrastes.yafrl.internal.Timeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * An event is a value which is defined in instantaneous moments at time.
 *
 * In other words, from a denotational perspective it can be thought
 *  of as being equivalent to a `List<Pair<Time, A>>`.
 *
 * Visually, you can think of a time as a graph, where the x-axis is time,
 *  and the y-axis is the value of the events:
 *
 * ```
 * ^
 * |              *
 * |        *               *
 * |   *                *            *
 * |                            *  *
 * |---------------------------------->
 * ```
 *
 * For convenience, an [Event] is also a [kotlinx.coroutines.flow.Flow], and so it can be [collect]ed on,
 *  but it may have slightly different behavior than most flows you are used to from
 *  kotlinx.coroutines such as [kotlinx.coroutines.flow.MutableSharedFlow].
 *
 * To use an [Event] idiomatically, you should avoid using [collect] unless absolutely
 *  necessary for your application -- and if necessary, [collect] should only be used
 *  at the "edges" of your application.
 **/
open class Event<out A> internal constructor(
    internal val node: Node<EventState<A>>
) {
    @FragileYafrlAPI
    suspend fun collect(collector: FlowCollector<A>) {
        node.collect { value ->
            if (value is EventState.Fired) {
                collector.emit(value.event)
            }
        }
    }

    /**
     * Applies the passed function [f] to each event that is emitted,
     *  producing a new transformed [Event] stream.
     *
     * Note: [f] should be a pure function.
     **/
    fun <B> map(f: (A) -> B): Event<B> {
        val graph = Timeline.currentTimeline()

        return Event(
            graph.createMappedNode(
                parent = node,
                f = {
                    it.map(f)
                },
                initialValue = lazy { EventState.None },
                onNextFrame = { node ->
                    node.rawValue = EventState.None
                }
            )
        )
    }

    /**
     * Applies the supplied function to each element of the
     *  [Event], and produces an event that only emits if
     *  the function evaluates to true.
     **/
    fun filter(f: (A) -> Boolean): Event<A> {
        val graph = Timeline.currentTimeline()

        return Event(
            graph.createMappedNode(
                parent = node,
                f = { event ->
                    if (event is EventState.Fired && f(event.event)) {
                        event
                    } else {
                        EventState.None
                    }
                },
                onNextFrame = { node ->
                    node.rawValue = EventState.None
                }
            )
        )
    }

    /**
     * Returns an [Event] that only fires if the [condition]
     * does not hold at a particular time.
     **/
    fun gate(condition: Behavior<Boolean>): Event<A> {
        val graph = Timeline.currentTimeline()

        return Event(
            graph.createMappedNode(
                parent = node,
                f = { event ->
                    if (event is EventState.Fired && !condition.value) {
                        event
                    } else {
                        EventState.None
                    }
                },
                onNextFrame = { node ->
                    node.rawValue = EventState.None
                }
            )
        )
    }

    /**
     * Takes and event, and constructs a moving window of [size]
     *  when events occur.
     *
     * This is useful for things like calculating moving averages
     *  for the values of an [Event].
     **/
    fun window(size: Int): Event<List<A>> = State.fold(listOf<A>(), this) { window, newValue ->
        if (window.size < size) {
            window + listOf(newValue)
        } else {
            window.drop(1) + listOf(newValue)
        }
    }
        .updated()

    /**
     * Blocks occurrence of events until the [window] of time has passed,
     *  after which the latest event will be emitted.
     **/
    @OptIn(FragileYafrlAPI::class)
    fun debounced(window: Duration): Event<A> {
        val debounced = internalBroadcastEvent<A>()

        val scope = Timeline.currentTimeline().scope

        var lastTime: Instant? = null
        var lastEvent: A? = null

        scope.launch {
            collect { event ->
                val currentTime = Clock.System.now()

                if (lastTime != null && currentTime - lastTime!! > window) {
                    debounced.send(lastEvent!!)
                }

                lastTime = currentTime
                lastEvent = event
            }
        }

        return debounced
    }

    /**
     * Creates a modified [Event] that only emits the latest value after the
     *  [duration] specified
     **/
    @OptIn(FragileYafrlAPI::class)
    fun throttled(duration: Duration): Event<A> {
        val throttled = internalBroadcastEvent<A>()

        val scope = Timeline.currentTimeline().scope

        var lastTime: Instant? = null
        var latestEvent: A? = null

        scope.launch {
            collect { event ->
                lastTime = Clock.System.now()
                latestEvent = event
            }
        }

        scope.launch {
            while (isActive) {
                if (latestEvent != null) {
                    delay(duration)
                    throttled.send(latestEvent!!)
                }
            }
        }

        return throttled
    }

    companion object {
        /**
         * Create an event that triggers every [delayTime].
         **/
        fun tick(delayTime: Duration): Event<Duration> {
            val event = internalBroadcastEvent<Duration>()

            val scope = Timeline.currentTimeline().scope

            scope.launch {
                while (isActive) {
                    delay(delayTime)
                    event.send(delayTime)
                }
            }

            return event
        }

        /**
         * Merges [Event]s using the [Leftmost][MergeStrategy.Leftmost] strategy.
         **/
        fun <A> merged(
            vararg events: Event<A>
        ): Event<A> {
            return mergedWith(
                MergeStrategy.Leftmost(),
                *events
            )
        }

        /**
         * Merges [Event]s using the supplied [MergeStrategy] to handle
         *  the case of simultaneous events.
         **/
        fun <A> mergedWith(
            strategy: MergeStrategy<A>,
            vararg events: Event<A>
        ): Event<A> {
            val graph = Timeline.currentTimeline()

            val parentNodes = events.map {
                it.node
            }

            var node: Node<EventState<A>>? = null
            node = graph.createCombinedNode(
                parentNodes,
                { values ->
                    val selected = strategy.mergeEvents(
                        values
                            .filterIsInstance<EventState.Fired<A>>()
                            .map { it.event }
                    )

                    if (selected != null) {
                        EventState.Fired(selected)
                    } else {
                        EventState.None
                    }
                },
                {
                    node!!.rawValue = EventState.None
                }
            )

            return Event(node)
        }
    }
}

/**
 * A [BroadcastEvent] is an [Event] that can have new values emitted to it.
 *
 * Usually used as an internal API to build up a new [Event].
 *
 * Created with the [broadcastEvent] function.
 **/
open class BroadcastEvent<A> internal constructor(
    node: Node<EventState<A>>
) : Event<A>(node) {
    fun send(value: A) {
        val timeline = Timeline.currentTimeline()

        timeline.updateNodeValue(node, EventState.Fired(value))
    }
}

/** Creates a [BroadcastEvent] for internal implementation purposes. */
internal fun <A> internalBroadcastEvent(
    label: String? = null
): BroadcastEvent<A> {
    val timeline = Timeline.currentTimeline()

    val initialValue = lazy { EventState.None }

    val node = timeline.createNode(
        value = initialValue,
        onNextFrame = { node ->
            node.rawValue = EventState.None
        },
        label = label
    )

    return BroadcastEvent(node)
}

/**
 * Constructs a new [BroadcastEvent].
 **/
fun <A> broadcastEvent(
    label: String? = null
): BroadcastEvent<A> {
    val timeline = Timeline.currentTimeline()

    val event = internalBroadcastEvent<A>(label)

    // If the user creates a broadcast event, assume it is an "external"
    //  input to the system.
    timeline.externalNodes[event.node.id] = event.node

    return event
}

/**
 * [onEvent] acts similarly to [map][Event.map], however, rather than transforming
 *  each value of the input [Event] by a pure synchronous function, [onEvent] will
 *  launch a [perform] coroutine for every frame in which [trigger] has fired.
 *
 * Essentially, [onEvent] lets us perform some action "outside" the regular [Timeline],
 *  and responds with a new [Event] that fires when said actions complete.
 *
 * Unlike [map][Event.map], for [onEvent] the [perform] action is encouraged to be
 *  a side-effecting function (making API calls, fetching data, etc...) if appropriate.
 *
 * Example:
 *
 * ```
 * val buttonClick: Event<Unit> = submitButton.clicks()
 *
 * val submitResponse: Event<DataResponse> = onEvent(buttonClick) {
 *     fetchData()
 * }
 * ```
 **/
@OptIn(FragileYafrlAPI::class)
fun <A, B> onEvent(trigger: Event<A>, perform: suspend (A) -> B): Event<B> {
    // Note: For now treat this as an external event, since the result from
    // perform could be nondeterministic.
    val responseEvent = broadcastEvent<B>()

    val scope = Timeline.currentTimeline().scope

    scope.launch {
        trigger.collect { event ->
            val result = perform(event)

            responseEvent.send(result)
        }
    }

    return responseEvent
}

/**
 * Used to help represent [Event]s as nodes.
 *
 * At each time an event is either [EventState.Fired],
 *  or there is [EventState.None].
 **/
internal sealed interface EventState<out A> {
    fun <B> map(f: (A) -> B): EventState<B>

    data class Fired<A>(val event: A) : EventState<A> {
        override fun <B> map(f: (A) -> B): EventState<B> {
            return Fired(f(event))
        }
    }

    data object None : EventState<Nothing> {
        override fun <B> map(f: (Nothing) -> B): EventState<B> {
            return None
        }
    }
}