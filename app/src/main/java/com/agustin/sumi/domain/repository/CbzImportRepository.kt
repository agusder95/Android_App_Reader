package com.agustin.sumi.domain.repository

import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzSourceReference

interface CbzImportRepository {
    /**
     * Returns the complete result after releasing import resources. The successful
     * result retains the original source and remains usable after temporary cleanup.
     * Propagates cancellation without publishing a result.
     */
    suspend fun importCbz(source: CbzSourceReference): CbzImportResult
}
