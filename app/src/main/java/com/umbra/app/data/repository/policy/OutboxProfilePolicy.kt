package com.umbra.app.data.repository.policy

internal object OutboxProfilePolicy {
    fun socialGraphLimit(profileKinds: Set<Int>, socialGraphKinds: Set<Int>): Int {
        return profileKinds.size + socialGraphKinds.size
    }
}