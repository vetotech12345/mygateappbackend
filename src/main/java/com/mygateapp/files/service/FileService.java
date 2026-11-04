package com.mygateapp.files.service;

import com.mygateapp.common.Hashing;
import com.mygateapp.config.AppProperties;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.files.entity.StoredFile;
import com.mygateapp.files.repository.StoredFileRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Validates and stores uploads and builds short-lived signed download URLs. The file type is decided from the file's
 * magic bytes, never from the client-supplied content type or file name.
 */
@Service
public class FileService {

    public static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    public static final Set<String> DOCUMENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    private final StoredFileRepository repository;
    private final FileStorage storage;
    private final AppProperties.Files config;
    private final byte[] signingKey;
    private final Clock clock;

    public FileService(StoredFileRepository repository, FileStorage storage, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.config = properties.files();
        this.signingKey = Hashing.deriveKey(properties.jwt().secret(), "file-url-signing");
        this.clock = clock;
    }

    /** Stores an image (JPEG, PNG or WebP). Returns null when {@code file} is null or empty-and-optional. */
    @Transactional
    public StoredFile storeImage(MultipartFile file, StoredFile.Purpose purpose, UUID societyId, UUID uploadedBy) {
        return store(file, purpose, societyId, uploadedBy, IMAGE_TYPES);
    }

    @Transactional
    public StoredFile storeDocument(MultipartFile file, StoredFile.Purpose purpose, UUID societyId, UUID uploadedBy) {
        return store(file, purpose, societyId, uploadedBy, DOCUMENT_TYPES);
    }

    private StoredFile store(MultipartFile file, StoredFile.Purpose purpose, UUID societyId, UUID uploadedBy,
                             Set<String> allowedTypes) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_FILE);
        }
        if (file.getSize() > config.maxSize().toBytes()) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                    "The file is too large. The maximum size is " + config.maxSize().toMegabytes() + " MB.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_FILE);
        }
        String contentType = sniff(bytes).filter(allowedTypes::contains)
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        allowedTypes.contains("application/pdf")
                                ? "Only JPEG, PNG, WebP images and PDF documents are allowed."
                                : "Only JPEG, PNG and WebP images are allowed."));
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String key = "%s/%d/%02d/%s%s".formatted(purpose.name().toLowerCase(), day.getYear(), day.getMonthValue(),
                UUID.randomUUID(), extension(contentType));
        storage.store(key, bytes);

        StoredFile stored = new StoredFile();
        stored.setSocietyId(societyId);
        stored.setUploadedBy(uploadedBy);
        stored.setPurpose(purpose);
        stored.setOriginalName(cleanName(file.getOriginalFilename()));
        stored.setContentType(contentType);
        stored.setSizeBytes(bytes.length);
        stored.setStorageKey(key);
        stored.setSha256(HexFormat.of().formatHex(Hashing.sha256(bytes)));
        stored.setCreatedAt(now);
        return repository.save(stored);
    }

    /** Signed, expiring URL that can be loaded without an Authorization header (e.g. by an image widget). */
    public String signedUrl(UUID fileId) {
        if (fileId == null) {
            return null;
        }
        long expires = clock.instant().plus(config.urlTtl()).getEpochSecond();
        String signature = sign(fileId, expires);
        String base = StringUtils.hasText(config.publicBaseUrl())
                ? config.publicBaseUrl().replaceAll("/+$", "")
                : currentBaseUrl();
        return base + "/api/v1/files/" + fileId + "?expires=" + expires + "&signature=" + signature;
    }

    public StoredFile verifyAndGet(UUID fileId, long expires, String signature) {
        if (expires < clock.instant().getEpochSecond() || !Hashing.constantTimeEquals(sign(fileId, expires), signature)) {
            throw new ApiException(ErrorCode.FILE_LINK_INVALID);
        }
        return repository.findById(fileId).orElseThrow(() -> new ApiException(ErrorCode.FILE_NOT_FOUND));
    }

    public byte[] content(StoredFile file) {
        try {
            return storage.load(file.getStorageKey());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND);
        }
    }

    /** Removes the bytes and metadata (retention). References must be cleared by the caller first. */
    @Transactional
    public void delete(UUID fileId) {
        repository.findById(fileId).ifPresent(file -> {
            repository.delete(file);
            storage.delete(file.getStorageKey());
        });
    }

    public Optional<StoredFile> find(UUID fileId) {
        return fileId == null ? Optional.empty() : repository.findById(fileId);
    }

    private String sign(UUID fileId, long expires) {
        return Hashing.hmacSha256Hex(signingKey, fileId + ":" + expires);
    }

    private static String currentBaseUrl() {
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        } catch (IllegalStateException noRequest) {
            return "";
        }
    }

    static Optional<String> sniff(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        if (bytes.length >= 12 && startsWith(bytes, 'R', 'I', 'F', 'F')
                && Arrays.equals(Arrays.copyOfRange(bytes, 8, 12), new byte[]{'W', 'E', 'B', 'P'})) {
            return Optional.of("image/webp");
        }
        if (startsWith(bytes, '%', 'P', 'D', 'F', '-')) {
            return Optional.of("application/pdf");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    private static String cleanName(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
        return base.length() > 200 ? base.substring(0, 200) : base;
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
