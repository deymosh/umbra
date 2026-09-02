package com.umbra.app.ui.components

import com.umbra.app.domain.nip01.Event

internal fun calculateThreadDepth(
    event: Event,
    eventsById: Map<String, Event>,
    maxDepth: Int = 4
): Int {
    var depth = 0
    var currentParent = event.getParentEventId()
    val visited = mutableSetOf<String>()

    while (currentParent != null && depth < maxDepth) {
        if (!visited.add(currentParent)) break
        val parent = eventsById[currentParent] ?: break
        depth += 1
        currentParent = parent.getParentEventId()
    }

    return depth
}

internal fun buildThreadDepthByEventId(
    events: List<Event>,
    eventsById: Map<String, Event>,
    maxDepth: Int = 4
): Map<String, Int> {
    return events.associate { event ->
        event.id to calculateThreadDepth(event, eventsById, maxDepth)
    }
}
