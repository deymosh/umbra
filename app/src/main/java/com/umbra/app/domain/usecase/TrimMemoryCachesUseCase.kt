package com.umbra.app.domain.usecase

import com.umbra.app.domain.logging.UmbraLogger
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shrinks every in-memory cache that doesn't otherwise react to real OS memory pressure — see
 * `UmbraApp.onTrimMemory`, which previously only cleared Coil's bitmap cache. Each cache keeps
 * its own normal ceiling/retention design (see EventLruCache's doc comment on why a GC-driven
 * redesign was deliberately deferred); this only asks each one to shed what it can spare right
 * now, and every cache is free to grow back afterward through ordinary use.
 */
class TrimMemoryCachesUseCase(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val contactListRepository: ContactListRepository,
    private val muteListRepository: MuteListRepository,
    private val pinListRepository: PinListRepository,
    private val logger: UmbraLogger
) {
    suspend operator fun invoke(aggressive: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                eventRepository.trimMemory(aggressive)
            } catch (e: Exception) {
                logger.e(e) { "eventRepository.trimMemory() failed during cache trim" }
            }
            try {
                userRepository.pruneStaleData()
            } catch (e: Exception) {
                logger.e(e) { "userRepository.pruneStaleData() failed during cache trim" }
            }
            try {
                contactListRepository.trimMemory()
            } catch (e: Exception) {
                logger.e(e) { "contactListRepository.trimMemory() failed during cache trim" }
            }
            try {
                muteListRepository.trimMemory()
            } catch (e: Exception) {
                logger.e(e) { "muteListRepository.trimMemory() failed during cache trim" }
            }
            try {
                pinListRepository.trimMemory()
            } catch (e: Exception) {
                logger.e(e) { "pinListRepository.trimMemory() failed during cache trim" }
            }
        }
    }
}
