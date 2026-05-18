package org.nrg.xnatx.plugins.transfer.util;

import org.nrg.xnatx.plugins.transfer.util.file.BatchTransferDirectoryCopy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.file.*;

@Slf4j
public class FileUtil {

    /**
     * Creates a link to a target and return the path of the link.
     *
     * @param linkFileName the file name of the link to create
     * @param linkFileName the target file name of the link
     * @return the resulting {@code Path}
     * @throws UnsupportedOperationException when the JVM doesn't support file links
     *                                       in a specific system
     * @throws IOException                   when an IO error occurs, e.g. invalid
     *                                       file path
     * @throws FileAlreadyExistsException    when the link file already exists, the
     *                                       override is not supported by default
     * @throws SecurityException             when the link file can't be created or
     *                                       the target file can't be accessed
     *                                       because of limited file permissions
     */
    public static Path createLinkFile(String targetFileName, String linkFileName, boolean recreateIfExisted)
            throws IOException {
        Path targetPath = Paths.get(targetFileName);
        Path linkPath = Paths.get(linkFileName);
        if (Files.exists(linkPath)) {
            if (!recreateIfExisted) {
                throw new FileAlreadyExistsException(linkPath.toString());
            }
            Files.delete(linkPath);
        }
        return Files.createLink(linkPath, targetPath);
    }

    public static Path copyFile(String srcFileName, String destFileName, boolean recreateIfExisted) throws IOException {
        Path srcPath = Paths.get(srcFileName);
        Path destPath = Paths.get(destFileName);
        if (Files.exists(destPath)) {
            if (!recreateIfExisted) {
                throw new FileAlreadyExistsException(destPath.toString());
            }
            Files.delete(destPath);
        }
        return Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String getDestFileName(Path srcPath, String srcRootDirName, String destRootDirName) {
        String srcPathName = srcPath.toString();
        if (srcPathName.indexOf(srcRootDirName) != 0) {
            throw new IllegalArgumentException("Invalid root dir name");
        }
        return destRootDirName + srcPathName.substring(srcRootDirName.length());
    }

    public static void linkRecursively(final Path srcRootPath, final Path destRootPath, final boolean recreateIfExisted)
            throws IOException {
        if (!Files.exists(srcRootPath)) {
            log.warn(String.format("Source folder (%s) doesn't exist. Copying will be skipped", srcRootPath));
            return;
        }

        Files.walkFileTree(srcRootPath,
                new BatchTransferDirectoryCopy(srcRootPath.toString(), destRootPath.toString(), recreateIfExisted, true));
    }

    public static void copyFiles(File source, File dest) throws Exception {
        try {
            FileUtils.copyDirectory(source, dest);
        } catch (Exception e) {
            final String msg = String.format("Failed to copy files from %s to %s", source.getAbsolutePath(), dest.getAbsolutePath());
            throw new Exception(msg, e);
        }
    }

    public static void copyFiles(String sourceFilePath, String destFilePath) throws Exception {
        File source = new File(sourceFilePath);
        File dest = new File(destFilePath);
        if (source.exists()) {
            dest.mkdirs();
            FileUtil.copyFiles(source, dest);
        }
    }

    public static void linkFiles(File source, File dest) throws Exception {
        try {
            FileUtil.linkRecursively(source.toPath(), dest.toPath(), true);
        } catch (Exception e) {
            final String msg = String.format("Failed to link files from %s to %s", source.getAbsolutePath(), dest.getAbsolutePath());
            throw new Exception(msg, e);
        }
    }

    public static void linkFiles(String sourceFilePath, String destFilePath) throws Exception {
        File source = new File(sourceFilePath);
        File dest = new File(destFilePath);
        if (source.exists()) {
            dest.mkdirs();
            FileUtil.linkFiles(source, dest);
        }
    }

    public static void deleteDirectoryWithoutException(File directory) {
        try {
            FileUtils.deleteDirectory(directory);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }
}
