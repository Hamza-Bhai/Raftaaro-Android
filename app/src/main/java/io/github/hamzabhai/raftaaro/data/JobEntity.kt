package io.github.hamzabhai.raftaaro.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.hamzabhai.raftaaro.core.AudioFormat
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobKind
import io.github.hamzabhai.raftaaro.core.JobStatus
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.VideoChoice

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val choiceMedia: String?,
    val choiceHeight: Int?,
    val choiceAudio: String?,
    val folder: String?,
    val status: String,
    val totalBytes: Long?,
    val receivedBytes: Long,
    val speedBps: Long,
    val etaSec: Long?,
    val error: String?,
    val attempts: Int,
    val retryAt: Long?,
    val createdAt: Long,
    val savedUri: String?,
    val fileName: String?
) {
    fun toJob() = Job(
        id = id, kind = JobKind.valueOf(kind), url = url, title = title, thumbnail = thumbnail,
        choice = choiceMedia?.let { VideoChoice(MediaType.valueOf(it), choiceHeight, choiceAudio?.let(AudioFormat::valueOf)) },
        folder = folder, status = JobStatus.valueOf(status), totalBytes = totalBytes, receivedBytes = receivedBytes,
        speedBps = speedBps, etaSec = etaSec, error = error, attempts = attempts, retryAt = retryAt,
        createdAt = createdAt, savedUri = savedUri, fileName = fileName
    )

    companion object {
        fun of(j: Job) = JobEntity(
            id = j.id, kind = j.kind.name, url = j.url, title = j.title, thumbnail = j.thumbnail,
            choiceMedia = j.choice?.media?.name, choiceHeight = j.choice?.height, choiceAudio = j.choice?.audioFormat?.name,
            folder = j.folder, status = j.status.name, totalBytes = j.totalBytes, receivedBytes = j.receivedBytes,
            speedBps = j.speedBps, etaSec = j.etaSec, error = j.error, attempts = j.attempts, retryAt = j.retryAt,
            createdAt = j.createdAt, savedUri = j.savedUri, fileName = j.fileName
        )
    }
}
