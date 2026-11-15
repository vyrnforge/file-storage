package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.application.FileApplication.MetadataView;
import io.vyrnforge.filestorage.application.FileApplication.View;
import io.vyrnforge.platform.kernel.file.FileReference;
import java.time.Instant;
import java.util.UUID;

/** Versioned logical projections; never serialize a persistence or placement aggregate. */
public final class FileDtos {
    private FileDtos() {}

    public record Initiation(String purpose, String name, Long expectedSize, String mimeType) {}

    public record SessionResponse(FileReference file, String versionId, String fileState,
            String versionState, String sessionId, String uploadState, Instant expiresAt) {}

    public record MetadataResponse(FileReference file, String versionId, String fileState,
            String versionState, String name, long declaredSize, String declaredMimeType) {}

    public record ErrorResponse(String code, UUID correlationId, UUID requestId) {}

    static SessionResponse session(View view) {
        return new SessionResponse(view.file(), view.version().value().toString(),
                view.fileState().name(), view.versionState().name(),
                view.session().value().toString(), view.uploadState().name(), view.expiresAt());
    }

    static MetadataResponse metadata(MetadataView view) {
        return new MetadataResponse(view.file(), view.version().value().toString(),
                view.fileState().name(), view.versionState().name(),
                view.name(), view.declaredSize(), view.declaredMimeType());
    }
}
