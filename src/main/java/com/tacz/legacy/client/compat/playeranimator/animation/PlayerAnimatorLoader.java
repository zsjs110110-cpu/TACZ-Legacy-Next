package com.tacz.legacy.client.compat.playeranimator.animation;

import com.google.gson.JsonParseException;
import com.tacz.legacy.TACZLegacy;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 扫描枪包里的 {@code assets/<namespace>/player_animator/<path>.json}。
 *
 * <p>上游 1.20 用 FileToIdConverter 遍历资源包；1.12.2 枪包是磁盘目录或 zip，
 * 这里用等价的正则逐个匹配。命中后由 [PlayerAnimatorAssetManager] 反序列化。
 */
public class PlayerAnimatorLoader {
    private static final Marker MARKER = MarkerManager.getMarker("PlayerAnimatorLoader");
    /**
     * 匹配任意前缀下的 {@code [assets/]<namespace>/player_animator/<path>.json}。
     * <p>
     * 1.12.2 的枪包既有目录也有 zip，zip 里常见多套一层（{@code 包名/assets/...}）甚至
     * {@code 包名/版本/assets/...}；上游 1.20 用 FileToIdConverter，只要路径里出现
     * {@code assets/<ns>/...} 就能识别，所以这里不再要求行首，改用 find() 从任意层级匹配。
     */
    private static final Pattern ANIMATOR_PATTERN = Pattern.compile(
            "(?:^|/)(?:assets/)?(?!assets/)([^/]+)/player_animator/([\\w/.-]+)\\.json$");

    private PlayerAnimatorLoader() {
    }

    public static boolean load(ZipFile zipFile, String zipPath) {
        Matcher matcher = ANIMATOR_PATTERN.matcher(zipPath.replace('\\', '/'));
        if (matcher.find()) {
            String namespace = matcher.group(1);
            String path = matcher.group(2);
            ZipEntry entry = zipFile.getEntry(zipPath);
            if (entry == null) {
                TACZLegacy.logger.warn(MARKER, "{} file doesn't exist", zipPath);
                return false;
            }
            try (InputStream stream = zipFile.getInputStream(entry)) {
                ResourceLocation registryName = new ResourceLocation(namespace, path);
                PlayerAnimatorAssetManager.get().putAnimation(registryName, stream);
                return true;
            } catch (IOException | JsonParseException | IllegalArgumentException exception) {
                TACZLegacy.logger.warn(MARKER, "Failed to read player animator file: {}, entry: {}", zipFile, entry, exception);
            }
        }
        return false;
    }

    /**
     * 扫描整个 zip 枪包。
     */
    public static void loadZip(File packFile) {
        try (ZipFile zipFile = new ZipFile(packFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                Matcher matcher = ANIMATOR_PATTERN.matcher(entry.getName().replace('\\', '/'));
                if (matcher.find()) {
                    load(zipFile, entry.getName());
                }
            }
        } catch (IOException exception) {
            TACZLegacy.logger.warn(MARKER, "Failed to scan player animator files in {}", packFile.getName(), exception);
        }
    }

    /**
     * 扫描目录枪包根（默认枪包会被导出成目录）。
     */
    public static void load(File packRoot) {
        final Path root = packRoot.toPath();
        final Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return;
        }
        try {
            Files.walkFileTree(assets, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String normalized = assets.relativize(file).toString().replace('\\', '/');
                    Matcher matcher = ANIMATOR_PATTERN.matcher(normalized);
                    if (!matcher.find()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String namespace = matcher.group(1);
                    String path = matcher.group(2);
                    ResourceLocation registryName = new ResourceLocation(namespace, path);
                    try (InputStream stream = Files.newInputStream(file)) {
                        PlayerAnimatorAssetManager.get().putAnimation(registryName, stream);
                    } catch (IOException | JsonParseException | IllegalArgumentException exception) {
                        TACZLegacy.logger.warn(MARKER, "Failed to read player animator file: {}", file, exception);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    TACZLegacy.logger.warn(MARKER, "Failed to visit {}", file, exception);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            TACZLegacy.logger.warn(MARKER, "Failed to walk player animator directory: {}", assets, exception);
        }
    }
}
