package com.example.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.BlockType
import com.example.data.local.IdeaBlockEntity
import com.example.data.local.LyricVaultDao
import com.example.data.local.LyricVaultDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Background transcription of one recorded audio clip.
 *
 * Runs in WorkManager, so it finishes even when the app is closed right
 * after recording: the work is queued when the idea is saved,
 * waits for a network connection, and retries with backoff on network
 * failures. A rejected API key fails permanently instead of looping.
 *
 * When it lands, the full transcript becomes a TEXT block immediately
 * after the audio block, and a short generated summary becomes the
 * audio block's label. Only idea audio blocks whose per-clip toggle
 * the user switched on are ever queued, so the summary always lands
 * as the label for those clips (replacing any earlier caption). The
 * toggle is edge-triggered: switching a clip off and on again and
 * saving re-transcribes it, adding another transcript block. There
 * is no transcription in songwriting mode.
 *
 * None of the writes touch the parent idea/song row, so the note's
 * "updated" timestamp never moves because of a transcription.
 *
 * The clip is identified by its file path (UUID-named per recording and
 * stable across editor saves, which rewrite block rows): if the block
 * is gone by the time the work runs, the job completes as a no-op.
 */
class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val targetType = inputData.getString(KEY_TARGET_TYPE)
            ?: return@withContext Result.failure()
        val ownerId = inputData.getLong(KEY_OWNER_ID, 0L)
        val audioPath = inputData.getString(KEY_AUDIO_PATH)
            ?: return@withContext Result.failure()
        if (ownerId <= 0L) return@withContext Result.failure()

        val audioFile = File(audioPath)
        // Clip deleted meanwhile: nothing to transcribe, ever.
        if (!audioFile.exists()) return@withContext Result.success()

        // Transcription switched off or key removed after queueing.
        val provider = TranscriptionProviders.create(applicationContext)
            ?: return@withContext Result.failure()

        val transcript: String = try {
            provider.transcribe(audioFile)
        } catch (e: TranscriptionException) {
            return@withContext if (e.httpCode == 401 || e.httpCode == 403) {
                Result.failure()
            } else {
                retryOrFail()
            }
        } catch (e: Exception) {
            return@withContext retryOrFail()
        }

        val summary: String = if (transcript.isBlank()) {
            // No speech (a hummed melody, an instrumental take): there is
            // no transcript to add, but the clip still earns a label.
            NO_SPEECH_LABEL
        } else {
            runCatching { provider.summarize(transcript) }.getOrDefault("")
        }

        val dao = LyricVaultDatabase.getInstance(applicationContext).lyricVaultDao()
        when (targetType) {
            TARGET_IDEA -> applyToIdea(dao, ownerId, audioPath, transcript, summary)
            else -> return@withContext Result.failure()
        }

        // Refresh the idea's embedding so the transcript text takes part
        // in matching. Best-effort, exactly like the save path.
        if (targetType == TARGET_IDEA) {
            runCatching {
                val service = AiMatchingService(
                    applicationContext,
                    dao,
                    AiSettings(applicationContext),
                    ApiKeyStore(applicationContext),
                    ModelStore(applicationContext)
                )
                dao.getIdeaByIdOnce(ownerId)?.let { service.embedIdea(it) }
            }
        }
        Result.success()
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount >= 3) Result.failure() else Result.retry()

    private suspend fun applyToIdea(
        dao: LyricVaultDao,
        ideaId: Long,
        audioPath: String,
        transcript: String,
        summary: String
    ) {
        val detail = dao.getIdeaByIdOnce(ideaId) ?: return
        val audioBlock = detail.orderedBlocks.firstOrNull {
            it.blockType == BlockType.AUDIO && it.audioFilePath == audioPath
        } ?: return
        if (transcript.isNotBlank()) {
            // Make room, then drop the transcript in right after the audio.
            dao.shiftBlockOrderAfter(ideaId, audioBlock.orderIndex)
            dao.insertBlock(
                IdeaBlockEntity(
                    ideaId = ideaId,
                    orderIndex = audioBlock.orderIndex + 1,
                    blockType = BlockType.TEXT,
                    textContent = transcript,
                    createdAt = audioBlock.createdAt + 1
                )
            )
        }
        // The clip was opted in per-block, so the summary becomes its
        // label — replacing whatever caption was there. If the summary
        // call produced nothing, fall back to the transcript's opening
        // words so the clip still gets a recognisable label.
        val label = summary.ifBlank {
            transcript.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(12)
                .joinToString(" ")
        }
        if (label.isNotBlank()) {
            dao.updateBlock(audioBlock.copy(textContent = label))
        }
    }

    companion object {
        const val KEY_TARGET_TYPE = "target_type"
        const val KEY_OWNER_ID = "owner_id"
        const val KEY_AUDIO_PATH = "audio_path"
        /** Work tag the UI watches to show the background-work indicator. */
        const val TAG = "transcription"
        const val TARGET_IDEA = "idea"
        const val NO_SPEECH_LABEL = "Hummed melody, no words"

        fun uniqueName(audioPath: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(audioPath.toByteArray())
            return "transcribe_" + digest.take(8)
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * Queue transcription for one clip. No-op when transcription is
         * off or no provider key is set. Callers queue only on a toggle
         * off -> on transition, so a clip CAN be transcribed again after
         * being re-armed — each run adds a fresh transcript block.
         */
        fun enqueue(
            context: Context,
            targetType: String,
            ownerId: Long,
            audioPath: String
        ) {
            if (ownerId <= 0L || audioPath.isBlank()) return
            if (TranscriptionProviders.create(context) == null) return
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_TARGET_TYPE, targetType)
                        .putLong(KEY_OWNER_ID, ownerId)
                        .putString(KEY_AUDIO_PATH, audioPath)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(audioPath),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
