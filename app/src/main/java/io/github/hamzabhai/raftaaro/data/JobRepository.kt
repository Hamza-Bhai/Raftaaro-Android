package io.github.hamzabhai.raftaaro.data

import io.github.hamzabhai.raftaaro.core.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The download list, newest first. */
class JobRepository(private val dao: JobDao) {
    val jobs: Flow<List<Job>> = dao.observeAll().map { list -> list.map { it.toJob() } }

    suspend fun all(): List<Job> = dao.all().map { it.toJob() }
    suspend fun get(id: String): Job? = dao.get(id)?.toJob()
    suspend fun add(job: Job) = dao.insert(JobEntity.of(job))
    suspend fun update(job: Job) = dao.update(JobEntity.of(job))
    suspend fun updateAll(jobs: List<Job>) = dao.updateAll(jobs.map(JobEntity::of))
    suspend fun delete(id: String) = dao.delete(id)
}
