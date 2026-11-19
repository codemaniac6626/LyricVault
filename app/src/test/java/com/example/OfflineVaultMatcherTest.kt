package com.example

import com.example.data.local.BlockType
import com.example.data.local.IdeaBlockEntity
import com.example.data.local.IdeaNoteEntity
import com.example.data.local.IdeaNoteWithDetails
import com.example.domain.matching.MatchMode
import com.example.domain.matching.OfflineVaultMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineVaultMatcherTest {

    private fun makeIdea(
        id: Long,
        title: String,
        body: String,
        contextNote: String = "",
        tags: String = "",
        usedCount: Int = 0
    ): IdeaNoteWithDetails {
        return IdeaNoteWithDetails(
            idea = IdeaNoteEntity(
                id = id,
                title = title,
                contextNote = contextNote,
                tags = tags,
                usedCount = usedCount
            ),
            blocks = listOf(
                IdeaBlockEntity(
                    id = id * 10,
                    ideaId = id,
                    orderIndex = 0,
                    blockType = BlockType.TEXT,
                    textContent = body
                )
            ),
            usages = emptyList()
        )
    }

    @Test
    fun vibeMatching_separatesFreshAndAlreadyUsedIdeas() {
        val ideas = listOf(
            makeIdea(
                id = 1,
                title = "Glass Masks",
                body = "Everyone rehearses a persona on the midnight subway",
                contextNote = "Good for a psychological verse about overthinking in a crowd",
                tags = "metro, ego, mask",
                usedCount = 0
            ),
            makeIdea(
                id = 2,
                title = "Neon Commute",
                body = "Quiet train window reflecting tired eyes at 2am",
                contextNote = "Late night solitude vibe",
                tags = "train, solitude",
                usedCount = 2
            ),
            makeIdea(
                id = 3,
                title = "Summer Beach",
                body = "Golden waves crashing on warm sand",
                tags = "ocean, sun",
                usedCount = 0
            )
        )

        val result = OfflineVaultMatcher.matchVault(
            ideas = ideas,
            queryText = "Late night metro ride overthinking masks people wear",
            mode = MatchMode.WHOLE_SONG_VIBE
        )

        assertTrue(result.freshUnusedMatches.any { it.ideaDetail.idea.id == 1L })
        assertTrue(result.alreadyUsedMatches.any { it.ideaDetail.idea.id == 2L })
        assertEquals(0, result.freshUnusedMatches.count { it.ideaDetail.idea.usedCount > 0 })
        assertEquals(0, result.alreadyUsedMatches.count { it.ideaDetail.idea.usedCount == 0 })
    }

    @Test
    fun specificLineMatching_boostsPhraseAndRhymeMatches() {
        val ideas = listOf(
            makeIdea(
                id = 10,
                title = "Mirror Echo",
                body = "Staring at the ghost inside the glass\nWatching every fleeting second pass",
                usedCount = 0
            ),
            makeIdea(
                id = 11,
                title = "Morning Coffee",
                body = "Fresh espresso steaming on the wooden table",
                usedCount = 0
            )
        )

        val result = OfflineVaultMatcher.matchVault(
            ideas = ideas,
            queryText = "I see a stranger in the glass",
            mode = MatchMode.SPECIFIC_LINE
        )

        assertTrue(result.freshUnusedMatches.isNotEmpty())
        assertEquals(10L, result.freshUnusedMatches.first().ideaDetail.idea.id)
    }
}
