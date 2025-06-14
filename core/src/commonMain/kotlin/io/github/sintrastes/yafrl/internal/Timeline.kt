package io.github.sintrastes.yafrl.internal

import io.github.sintrastes.yafrl.Event
import io.github.sintrastes.yafrl.State
import io.github.sintrastes.yafrl.EventState
import io.github.sintrastes.yafrl.annotations.FragileYafrlAPI
import io.github.sintrastes.yafrl.broadcastEvent
import io.github.sintrastes.yafrl.internalBindingState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Simple implementation of a push-pull FRP system using a graph
 *  of nodes.
 *
 * Changes are propagated by marking child nodes as dirty when a parent value
 *  changes.
 *
 * Dirty values are recomputed lazily upon request.
 **/
class Timeline(
    val scope: CoroutineScope,
    private val timeTravelEnabled: Boolean,
    private val debugLogging: Boolean,
    private val lazy: Boolean,
    initClock: (State<Boolean>) -> Event<Duration>
) : SynchronizedObject() {
    @OptIn(FragileYafrlAPI::class)
    val time: Duration
        get() = fetchNodeValue(timeBehavior.node) as Duration

    val timeBehavior by lazy {
        clock.scan(0.0.seconds) { x, y ->
            x + y
        }
    }

    // Needs to be internal because we can't undo a "pause" event.
    @OptIn(FragileYafrlAPI::class)
    val pausedState by lazy {
        internalBindingState(
            lazy { false },
            "__internal_paused"
        )
    }

    // The clock is lazily initialized so that if an explicit clock is not used,
    //  it will not start ticking.
    val clock: Event<Duration> by lazy {
        initClock(pausedState)
        // Theoretically gate should work for this, but not
        // currently working.
        //.gate(pausedState)
    }

    private var latestID = -1

    private fun newID(): NodeID {
        latestID++

        return NodeID(latestID)
    }

    /////////// Basically we are building up a doubly-linked DAG here: /////////////

    var latestFrame: Long = -1
    var currentFrame: Long = -1

    // Indexed by frame number.
    val previousStates: MutableMap<Long, GraphState> = mutableMapOf()

    data class GraphState(
        val nodeValues: PersistentMap<NodeID, Any?>,
        val children: PersistentMap<NodeID, PersistentList<NodeID>>
    )

    @OptIn(FragileYafrlAPI::class)
    fun persistState() {
        if (timeTravelEnabled) {
            if (debugLogging) println("Persisting state in frame ${latestFrame}, ${nodes.size} nodes")
            previousStates[latestFrame] = GraphState(
                nodes
                    .mapValues { it.value.rawValue }
                    .toPersistentMap(),
                children
            )
        }
    }

    @OptIn(FragileYafrlAPI::class)
    fun resetState(frame: Long) = synchronized(this) {
        val time = measureTime {
            if (debugLogging) println("Resetting to frame ${frame}, event was: ${eventTrace.getOrNull(frame.toInt())}")
            val nodeValues = previousStates[frame]
                ?.nodeValues ?: run {
                    if (debugLogging) println("No previous state found for frame ${frame}")
                    return@synchronized
                }

            nodes.values.forEach { node ->
                if (node.label == "__internal_paused") return@forEach

                val resetValue = nodeValues[node.id]

                if (resetValue != null) {
                    updateNodeValue(node, resetValue)
                    node.onRollback?.invoke(node, frame)
                }
            }

            children = previousStates[frame]!!.children
            latestFrame = frame
        }

        if (debugLogging) println("Reset state in ${time}")
    }

    fun rollbackState() {
        resetState(latestFrame - 1)
    }

    fun nextState() {
        resetState(latestFrame + 1)
    }

    private var nodes: PersistentMap<NodeID, Node<Any?>> = persistentMapOf()

    // Map from a node ID to it's set of child nodes
    private var children: PersistentMap<NodeID, PersistentList<NodeID>> = persistentMapOf()

    ///////////////////////////////////////////////////////////////////////////////

    data class ExternalEvent(
        val id: NodeID,
        val value: Any?
    )

    /**
     * Log of all external events that have been emitted into the timeline.
     *
     * Only works if [timeTravelEnabled]
     **/
    internal val eventTrace = mutableListOf<ExternalEvent>()

    val onNextFrameListeners = mutableListOf<() -> Unit>()

    data class ExternalNode(
        val type: KType,
        val node: Node<*>
    )

    /** Keep track of any external (or "input") nodes to the system. */
    @FragileYafrlAPI
    val externalNodes = mutableMapOf<NodeID, ExternalNode>()

    @FragileYafrlAPI
    fun <A> createNode(
        value: Lazy<A>,
        onUpdate: (Node<A>) -> A = { it -> it.rawValue },
        onNextFrame: ((Node<A>) -> Unit)? = null,
        onRollback: ((node: Node<A>, frame: Long) -> Unit)? = null,
        label: String? = null,
    ): Node<A> = synchronized(this) {
        val id = newID()

        var newNode: Node<A>? = null
        newNode = Node(
            value,
            id,
            { onUpdate(newNode!!) },
            onNextFrame,
            onRollback,
            label ?: id.toString()
        )

        nodes = nodes.put(id, newNode)

        // This persist state causes a stack overflow in drawing test with time travel enabled
        //persistState()

        return newNode
    }

    @OptIn(FragileYafrlAPI::class)
    internal fun <A, B> createMappedNode(
        parent: Node<A>,
        f: (A) -> B,
        initialValue: Lazy<B> = lazy {
            f(fetchNodeValue(parent) as A)
        },
        onNextFrame: ((Node<B>) -> Unit)? = null
    ): Node<B> = synchronized(this) {
        val mapNodeID = newID()

        var mappedNode: Node<B>? = null
        mappedNode = Node(
            initialValue = initialValue,
            id = mapNodeID,
            recompute = {
                val parentValue = fetchNodeValue(parent) as A

                val result = f(parentValue)

                result
            },
            onNextFrame = onNextFrame
        )

        nodes = nodes.put(mapNodeID, mappedNode)

        val mapChildren = children[parent.id]

        children = if (mapChildren != null) {
            children.put(parent.id, mapChildren.add(mapNodeID))
        } else {
            children.put(parent.id, persistentListOf(mapNodeID))
        }

        persistState()

        return mappedNode
    }

    @OptIn(FragileYafrlAPI::class)
    fun <A, B> createFoldNode(
        initialValue: A,
        eventNode: Node<EventState<B>>,
        reducer: (A, B) -> A
    ): Node<A> = synchronized(this) {
        val foldNodeID = newID()

        val createdFrame = currentFrame

        var events = listOf<B>()

        var currentValue = initialValue

        val foldNode = Node(
            initialValue = lazy { initialValue },
            id = foldNodeID,
            recompute = {
                val event = fetchNodeValue(eventNode) as EventState<B>
                if (event is EventState.Fired) {
                    currentValue = reducer(currentValue, event.event)

                    if (timeTravelEnabled) {
                        events += event.event
                    }
                }

                currentValue
            },
            onRollback = { node, frame ->
                val resetTo = frame - createdFrame

                events = events.take(resetTo.toInt())

                currentValue = events.fold(initialValue, reducer)

                node.rawValue = currentValue
            }
        )

        nodes = nodes.put(foldNodeID, foldNode)

        val eventChildren = children[eventNode.id]

        children = if (eventChildren != null) {
            children.put(eventNode.id, eventChildren.add(foldNodeID))
        } else {
            children.put(eventNode.id, persistentListOf(foldNodeID))
        }

        persistState()

        return foldNode
    }

    @OptIn(FragileYafrlAPI::class)
    internal fun <A> createCombinedNode(
        parentNodes: List<Node<Any?>>,
        combine: (List<Any?>) -> A,
        onNextFrame: ((Node<A>) -> Unit)? = null
    ): Node<A> = synchronized(this) {
        val combinedNodeID = newID()

        val initialValue = lazy { combine(parentNodes.map { it.rawValue }) }

        val combinedNode = Node(
            initialValue = initialValue,
            id = combinedNodeID,
            recompute = {
                combine(parentNodes.map { fetchNodeValue(it) as A })
            },
            onNextFrame = onNextFrame
        )

        nodes = nodes.put(combinedNodeID, combinedNode)

        for (parentNode in parentNodes) {
            val nodeChildren = children[parentNode.id]

            children = if (nodeChildren != null) {
                children.put(parentNode.id, nodeChildren.add(combinedNodeID))
            } else {
                children.put(parentNode.id, persistentListOf(combinedNodeID))
            }
        }

        // This persist state causes a stack overflow in drawing test with time travel enabled
        // persistState()

        return combinedNode
    }

    // Note: This is the entrypoint for a new "frame" in the timeline.
    @FragileYafrlAPI
    fun updateNodeValue(
        node: Node<Any?>,
        newValue: Any?,
        internal: Boolean = false
    ) = synchronized(this) {
        if (debugLogging && !internal) println("Updating node ${node.label} to ${newValue}")

        if (!internal) {
            for (listener in onNextFrameListeners) {
                if (debugLogging) println("Invoking on next frame listener for ${node.label}")
                listener()
            }

            onNextFrameListeners.clear()
        }

        node.rawValue = newValue

        if (!internal && timeTravelEnabled && externalNodes.contains(node.id)) {
            latestFrame++
            currentFrame++

            eventTrace += ExternalEvent(node.id, node.rawValue)

            if (debugLogging) println("${latestFrame}: Updating node ${node.label} to $newValue")
        }

        for (listener in node.syncOnValueChangedListeners) {
            listener.invoke(newValue)
        }

        if (node.onValueChangedListeners.isNotEmpty()) {
            scope.launch {
                for (listener in node.onValueChangedListeners) {
                    listener.emit(newValue)
                }
            }
        }

        if (!internal && node.onNextFrame != null) {
            if (debugLogging) println("Adding on next frame listener for ${node.label}")
            onNextFrameListeners.add { node.onNextFrame!!.invoke(node) }
        }

        updateChildNodes(node)

        persistState()
    }

    @OptIn(FragileYafrlAPI::class)
    private fun updateChildNodes(
        node: Node<Any?>
    ) {
        val childNodes = children[node.id] ?: listOf()

        for (childID in childNodes) {
            val child = nodes[childID]!!

            if (child.onNextFrame != null) {
                if (debugLogging) println("Adding on next frame listener for ${child.label}")
                onNextFrameListeners.add { child.onNextFrame!!.invoke(child) }
            }

            if (debugLogging) println("Updating child node of ${node.label}")

            if (lazy && child.onValueChangedListeners.size == 0 &&
                child.syncOnValueChangedListeners.size == 0) {
                if (debugLogging) println("Marking child ${child.label} dirty")
                // If not listening, we can mark the node dirty
                child.dirty = true
            } else {
                // Otherwise, we are forced to calculate the node's value
                val newValue = child.recompute()

                if(debugLogging) println("Recomputing child ${child.label} := $newValue")

                child.rawValue = newValue

                // As well as invoking any listeners on the child.
                for (listener in child.syncOnValueChangedListeners) {
                    listener.invoke(child.rawValue)
                }

                if (child.onValueChangedListeners.isNotEmpty()) {
                    scope.launch {
                        for (listener in child.onValueChangedListeners) {
                            listener.emit(child.rawValue)
                        }
                    }
                }
            }

            updateChildNodes(child)
        }
    }

    @OptIn(FragileYafrlAPI::class)
    internal fun fetchNodeValue(
        node: Node<Any?>
    ): Any? = synchronized(this) {
        if (!node.dirty) {
            return node.rawValue
        } else {
            node.rawValue = node.recompute()
            node.dirty = false
        }

        return node.rawValue
    }

    companion object {
        private var _timeline: Timeline? = null

        fun initializeTimeline(
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            timeTravel: Boolean = false,
            debug: Boolean = false,
            lazy: Boolean = true,
            // Use a trivial (discrete) clock by default.
            initClock: (State<Boolean>) -> Event<Duration> = {
                broadcastEvent<Duration>("clock")
            }
        ) {
            _timeline = Timeline(scope, timeTravel, debug, lazy, initClock)
        }

        fun currentTimeline(): Timeline {
            return _timeline
                ?: error("Timeline must be initialized with Timeline.initializeTimeline().")
        }
    }
}

fun <A> Node<A>.current(): A {
    val graph = Timeline.currentTimeline()

    return graph.fetchNodeValue(this) as A
}