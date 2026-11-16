package com.acme.assetmanagement.asset;

import com.acme.assetmanagement.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AssetImageStorageService {
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    static final int MAX_IMAGE_URL_LENGTH = 1024;
    static final String PUBLIC_URL_PREFIX = "/api/public/asset-images/";
    private static final Pattern DATA_URL = Pattern.compile(
            "^data:(image/(?:png|jpeg|webp));base64,(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STORED_FILE_NAME = Pattern.compile("^[a-f0-9]{32}\\.(?:png|jpg|webp)$");

    private final Path directory;
    private final AssetRepository repository;
    private final AssetTagSequenceRepository sequenceRepository;
    private final java.util.concurrent.locks.ReentrantLock imageChanges = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.Set<String> deferredDeletes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public AssetImageStorageService(@Value("${app.asset-images.directory:./data/asset-images}") String directory,
                                   AssetRepository repository, AssetTagSequenceRepository sequenceRepository) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.repository = repository;
        this.sequenceRepository = sequenceRepository;
    }

    public List<String> persistReferences(List<String> references) {
        if (!references.isEmpty()) lockImageChanges();
        return references.stream().map(this::persistReference).toList();
    }

    private void lockImageChanges() {
        // Serialize database changes, then retain the local file lock through post-commit cleanup.
        sequenceRepository.findLockedByDate(AssetTagGenerator.ALLOCATION_LOCK_DATE).orElseThrow();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().stream()
                .noneMatch(sync -> sync instanceof ImageChangeLock)) {
            imageChanges.lock();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new ImageChangeLock());
        }
    }

    private class ImageChangeLock implements org.springframework.transaction.support.TransactionSynchronization {
        @Override public void afterCompletion(int status) { imageChanges.unlock(); }
    }

    public Resource load(String fileName) {
        if (!STORED_FILE_NAME.matcher(fileName).matches()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        Path file = directory.resolve(fileName).normalize();
        if (!file.getParent().equals(directory) || !Files.isRegularFile(file)
                || repository.countImageReferences(PUBLIC_URL_PREFIX + fileName) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        return new FileSystemResource(file);
    }

    public String mediaType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private String persistReference(String value) {
        return processReference(value, true);
    }

    public void validateReference(String value) { processReference(value, false); }

    private String processReference(String value, boolean persist) {
        String reference = value == null ? "" : value.trim();
        if (reference.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "资产图片不能为空");
        if (!reference.regionMatches(true, 0, "data:", 0, 5)) {
            if (reference.length() > MAX_IMAGE_URL_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "资产图片地址不能超过1024个字符");
            }
            if (reference.startsWith(PUBLIC_URL_PREFIX)) {
                String fileName = reference.substring(PUBLIC_URL_PREFIX.length());
                if (!STORED_FILE_NAME.matcher(fileName).matches() || !Files.isRegularFile(directory.resolve(fileName))) {
                    throw new ApiException(HttpStatus.CONFLICT, "资产图片已不存在，请移除失效图片或重新上传");
                }
            }
            return reference;
        }

        Matcher matcher = DATA_URL.matcher(reference);
        if (!matcher.matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "资产图片仅支持JPG、PNG或WebP格式");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(matcher.group(2));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "资产图片数据格式不正确");
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "单张资产图片不能超过2MB");
        }

        String mediaType = matcher.group(1).toLowerCase(Locale.ROOT);
        validateSignature(bytes, mediaType);
        if (!persist) return reference;
        String extension = mediaType.equals("image/png") ? "png" : mediaType.equals("image/webp") ? "webp" : "jpg";
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(fileName), bytes, StandardOpenOption.CREATE_NEW);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) deleteFile(fileName);
                        }
                    });
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "资产图片文件保存失败");
        }
        return PUBLIC_URL_PREFIX + fileName;
    }

    public void removeAfterCommit(List<String> references) {
        var candidates = java.util.stream.Stream.concat(
                references.stream().filter(java.util.Objects::nonNull).filter(value -> value.startsWith(PUBLIC_URL_PREFIX)),
                deferredDeletes.stream().map(name -> PUBLIC_URL_PREFIX + name)).distinct().toList();
        if (candidates.isEmpty()) return;
        lockImageChanges();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    private final java.util.List<String> unused = new java.util.ArrayList<>();
                    @Override public void beforeCommit(boolean readOnly) {
                        repository.flush();
                        candidates.forEach(reference -> {
                            if (repository.countImageReferences(reference) == 0) unused.add(reference);
                            else deferredDeletes.remove(reference.substring(PUBLIC_URL_PREFIX.length()));
                        });
                    }
                    @Override public void afterCommit() {
                        unused.forEach(reference -> deleteFile(reference.substring(PUBLIC_URL_PREFIX.length())));
                    }
                });
    }

    private void deleteFile(String fileName) {
        if (!STORED_FILE_NAME.matcher(fileName).matches()) return;
        // The backup creates this guard before opening its database snapshot. A deletion which
        // passed this check earlier belongs to an already committed transaction, so that snapshot
        // cannot reference it. Image contents are immutable; new images need no deletion guard.
        if (Files.exists(directory.resolve(".backup-in-progress"))) {
            deferredDeletes.add(fileName);
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve(fileName));
            deferredDeletes.remove(fileName);
        }
        catch (IOException exception) {
            deferredDeletes.add(fileName);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unable to remove unreferenced asset image {}", fileName, exception);
        }
    }

    private void validateSignature(byte[] bytes, String mediaType) {
        boolean valid = switch (mediaType) {
            case "image/png" -> bytes.length >= 8
                    && unsigned(bytes[0]) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
            case "image/jpeg" -> bytes.length >= 3
                    && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8 && unsigned(bytes[2]) == 0xff;
            case "image/webp" -> bytes.length >= 12
                    && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
        if (!valid) throw new ApiException(HttpStatus.BAD_REQUEST, "资产图片内容与文件格式不匹配");
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }
}
