package com.mygateapp.files.service;

import com.mygateapp.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/**
 * Local-disk storage for uploaded files (a mounted volume in Docker). Keys are generated server-side and resolved
 * strictly inside the storage directory. Swap for an object-store implementation when scaling out.
 */
@Component
public class FileStorage {

    private final Path root;

    public FileStorage(AppProperties properties) {
        this.root = Path.of(properties.files().storageDir()).toAbsolutePath().normalize();
    }

    public void store(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store file", e);
        }
    }

    public byte[] load(String key) throws IOException {
        return Files.readAllBytes(resolve(key));
    }

    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete file", e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }
}
