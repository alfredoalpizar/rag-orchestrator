package com.alfredoalpizar.rag.model.response

import com.alfredoalpizar.rag.service.ingestion.IngestionResult
import com.alfredoalpizar.rag.service.ingestion.IngestionStatistics
import com.alfredoalpizar.rag.service.ingestion.IngestionStatus
import com.alfredoalpizar.rag.service.ingestion.IngestionSummary

data class IngestDocumentResponse(
    val status: String,
    val docId: String?,
    val chunksCreated: Int?,
    val message: String?
) {
    companion object {
        fun from(result: IngestionResult) = IngestDocumentResponse(
            status = result.status.name.lowercase(),
            docId = result.docId,
            chunksCreated = result.chunksCreated,
            message = result.error
        )
    }
}

data class IngestDirectoryResponse(
    val totalFiles: Int,
    val successful: Int,
    val skipped: Int,
    val failed: Int,
    val results: List<DocumentResult>,
    val error: String?
) {
    companion object {
        fun from(summary: IngestionSummary) = IngestDirectoryResponse(
            totalFiles = summary.totalFiles,
            successful = summary.successful,
            skipped = summary.skipped,
            failed = summary.failed,
            results = summary.results.map { DocumentResult.from(it) },
            error = summary.error
        )
    }
}

data class DocumentResult(
    val fileName: String,
    val status: String,
    val docId: String?,
    val chunksCreated: Int?,
    val error: String?
) {
    companion object {
        fun from(result: IngestionResult) = DocumentResult(
            fileName = result.fileName,
            status = result.status.name.lowercase(),
            docId = result.docId,
            chunksCreated = result.chunksCreated,
            error = result.error
        )
    }
}

data class IngestionStatsResponse(
    val totalChunks: Int,
    val embeddingProvider: String,
    val embeddingModel: String,
    val embeddingDimensions: Int
) {
    companion object {
        fun from(stats: IngestionStatistics) = IngestionStatsResponse(
            totalChunks = stats.totalChunks,
            embeddingProvider = stats.embeddingProvider,
            embeddingModel = stats.embeddingModel,
            embeddingDimensions = stats.embeddingDimensions
        )
    }
}

data class DeleteDocumentResponse(
    val docId: String,
    val message: String
)
