package edu.sadp.checkcollect.poi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 照片文件存储。图片保存在服务端目录，数据库记录相对路径与元数据（architecture.md 第 2 节）。
 * 相对路径格式：{poiId}/{revisionId}-{photoId}.{ext}，读写均限制在配置目录内。
 */
@Component
public class PhotoStorage {

    public static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");

    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{2,5}");

    private final Path root;

    public PhotoStorage(@Value("${app.photo.dir:./data/photos}") String directory) {
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    public StoredPhoto store(UUID poiId, UUID revisionId, UUID photoId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "照片为空，请重新拍摄或选择图片");
        }
        String contentType = normaliseType(file.getContentType());
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 JPEG、PNG、WebP 或 HEIC 格式的照片");
        }
        String extension = extension(file.getOriginalFilename(), contentType);
        String relative = poiId + "/" + revisionId + "-" + photoId + "." + extension;
        Path target = resolveInsideRoot(relative);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "照片保存失败，请稍后重试", error);
        }
        return new StoredPhoto(relative, contentType, file.getSize());
    }

    public byte[] read(String relativePath) {
        Path target = resolveInsideRoot(relativePath);
        if (!Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "照片文件不存在");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "照片读取失败", error);
        }
    }

    public Resource load(String relativePath) {
        Path target = resolveInsideRoot(relativePath);
        if (!Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "照片文件不存在");
        }
        return new FileSystemResource(target);
    }

    private Path resolveInsideRoot(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "照片文件不存在");
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "照片路径不合法");
        }
        return target;
    }

    public static String normaliseType(String contentType) {
        if (contentType == null || contentType.isBlank()) return "";
        String type = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return type.equals("image/jpg") ? "image/jpeg" : type;
    }

    private static String extension(String fileName, String contentType) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String candidate = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (SAFE_EXTENSION.matcher(candidate).matches()) return candidate;
            }
        }
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic", "image/heif" -> "heic";
            default -> "jpg";
        };
    }

    public record StoredPhoto(String relativePath, String contentType, long byteSize) {}
}
