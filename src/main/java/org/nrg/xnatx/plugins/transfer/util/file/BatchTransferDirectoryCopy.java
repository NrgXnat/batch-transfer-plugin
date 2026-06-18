package org.nrg.xnatx.plugins.transfer.util.file;

import org.nrg.xnatx.plugins.transfer.util.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.nrg.xnat.utils.CatalogUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j

public class BatchTransferDirectoryCopy extends SimpleFileVisitor<Path> {

    private final String sourcePath;
    private final String destinationPath;
    private final boolean recreateIfExisted;
    private final boolean useHardLink;
    private final List<String> filesToExclude;

    /**
     * Copies one directory to another
     *
     * @param sourcePath        - The source directory name
     * @param destinationPath   - The destination directory name
     * @param recreateIfExisted - Overwrite the file if it already exists in the destination directory
     * @param useHardLink       - Use hardlinks when possible (Catalog files will still be copied).
     */
    public BatchTransferDirectoryCopy(final String sourcePath,
                                   final String destinationPath,
                                   final boolean recreateIfExisted,
                                   final boolean useHardLink) {
        this.sourcePath = sourcePath;
        this.destinationPath = destinationPath;
        this.recreateIfExisted = recreateIfExisted;
        this.useHardLink = useHardLink;
        this.filesToExclude = null;
    }

    /**
     * Copies one directory into another directory and excludes specific files.
     * Unfortunately, this will still create directories even if all files within the directory are in the exclude list.
     *
     * @param sourcePath      - The source directory name
     * @param destinationPath - The destination directory name
     * @param filesToExclude  - A list of file names to exclude
     * @param recreateIfExisted - Overwrite the file if it already exists in the destination directory
     * @param useHardLink       - Use hardlinks when possible (Catalog files will still be copied).
     */
    public BatchTransferDirectoryCopy(final String sourcePath,
                                   final String destinationPath,
                                   final List<File> filesToExclude,
                                   final boolean recreateIfExisted,
                                   final boolean useHardLink) {
        this.sourcePath = sourcePath;
        this.destinationPath = destinationPath;
        this.recreateIfExisted = recreateIfExisted;
        this.useHardLink = useHardLink;
        this.filesToExclude = filesToExclude.stream().map(File::getAbsolutePath).collect(Collectors.toList());
    }

    protected boolean skipFile(Path file) {
        return filesToExclude != null && filesToExclude.contains(file.toFile().getAbsolutePath());
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {
        try {
            if (attr.isRegularFile() && !attr.isSymbolicLink()) {
                if (skipFile(file)) {
                    log.debug("Skipping file: {}", file.toFile().getAbsolutePath());
                    return FileVisitResult.CONTINUE;
                }

                String destFileName = FileUtil.getDestFileName(file, sourcePath, destinationPath);
                if (!isCatalogFile(file) && useHardLink) {
                    log.debug("Creating a link for {} to {}", file, destFileName);
                    FileUtil.createLinkFile(file.toString(), destFileName, recreateIfExisted);
                } else {
                    log.debug("Copying {} to {}", file, destFileName);
                    FileUtil.copyFile(file.toString(), destFileName, recreateIfExisted);
                }
            } else {
                log.warn("Skipping other file type: " + file.toString());
            }
        } catch (FileAlreadyExistsException e) {
            log.warn("File Already exists! Skipped");
        }

        return FileVisitResult.CONTINUE;
    }

    private static boolean isCatalogFile(Path file) {
        final String name = file.getFileName().toString();
        if (name.endsWith("_catalog.xml")) return true;     // standard catalogs
        if (!name.endsWith(".xml"))        return false;     // DICOM/data: never read them
        return CatalogUtils.isCatalogFile(file.toFile());    // rare odd-named .xml: still sniff
    }

    @Override
    public FileVisitResult preVisitDirectory(Path file, BasicFileAttributes attr) throws IOException {
        final String destFileName = FileUtil.getDestFileName(file, sourcePath, destinationPath);
        final File destFile = Paths.get(destFileName).toFile();
        log.debug(String.format("Creating a folder to %s", destFileName));

        if (destFile.exists()) {
            return FileVisitResult.CONTINUE;
        }

        Files.createDirectories(Paths.get(destFileName));
        return FileVisitResult.CONTINUE;
    }
}
