package dev.usagetime.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryRebuildTest {
    @Test fun untouchedReplaySkipsRebuild(){
        assertFalse(SummaryRebuild.needed(wroteSlices = false, closureAt = null, rewound = false))
    }

    @Test fun anyWrittenSliceRequiresRebuild(){
        assertTrue(SummaryRebuild.needed(wroteSlices = true, closureAt = null, rewound = false))
    }

    @Test fun closureFinalisationRequiresRebuild(){
        assertTrue(SummaryRebuild.needed(wroteSlices = false, closureAt = 1_000L, rewound = false))
    }

    @Test fun rewindRequiresRebuildEvenWithoutNewSlices(){
        assertTrue(SummaryRebuild.needed(wroteSlices = false, closureAt = null, rewound = true))
    }
}
