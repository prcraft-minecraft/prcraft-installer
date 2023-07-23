package io.github.gaming32.prcraftinstaller;

import at.spardat.xma.xdelta.JarPatcher;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.*;
import java.util.Collections;

public class PrcraftInstaller {
    public static void runInstaller(Path outputFile) throws IOException {
        final Path mappingsZipPath = Files.createTempFile("prcraft", ".zip");
        final Path unmappedClientPath = Files.createTempFile("prcraft", ".jar");
        final Path mappedClientPath = Files.createTempFile("prcraft", ".jar");
        Files.deleteIfExists(mappedClientPath);
        try {
            downloadFile(mappingsZipPath, "https://mcphackers.org/versions/1.2.5.zip");
            downloadFile(unmappedClientPath, "https://launcher.mojang.com/v1/objects/4a2fac7504182a97dcbcd7560c6392d7c8139928/client.jar");
            remapClient(mappingsZipPath, unmappedClientPath, mappedClientPath);

            final SeekableByteChannel patchChannel = new SeekableInMemoryByteChannel();
            downloadFile(Channels.newOutputStream(patchChannel), "https://github.com/Gaming32/prcraft-installer-data/raw/main/prcraft-patch.zip");
            try (
                ZipFile patch = new ZipFile(patchChannel);
                ZipFile input = new ZipFile(mappedClientPath);
                ZipArchiveOutputStream output = new ZipArchiveOutputStream(outputFile);
                BufferedReader patchList = new BufferedReader(new InputStreamReader(patch.getInputStream(patch.getEntry("META-INF/file.list"))))
            ) {
                output.putArchiveEntry(new ZipArchiveEntry("track.txt"));
                downloadFile(output, "https://github.com/Gaming32/prcraft-installer-data/raw/main/buildnumber");
                output.flush();
                output.closeArchiveEntry();

                patchList.readLine();
                patchList.readLine();
                new JarPatcher("prcraft-patch.zip", "1.2.5-mapped.jar")
                    .applyDelta(patch, input, output, patchList, "");
            }
        } finally {
            Files.deleteIfExists(mappingsZipPath);
            Files.deleteIfExists(unmappedClientPath);
            Files.deleteIfExists(mappedClientPath);
        }
    }

    private static void downloadFile(Path dest, String url) throws IOException {
        try (InputStream is = new URL(url).openStream()) {
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void downloadFile(OutputStream dest, String url) throws IOException {
        try (InputStream is = new URL(url).openStream()) {
            IOUtils.copy(is, dest);
        }
    }

    private static void remapClient(Path mappingsZipPath, Path unmappedClientPath, Path destPath) throws IOException {
        try (FileSystem zipFs = FileSystems.newFileSystem(mappingsZipPath, null)) {
            final TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(
                    Files.newBufferedReader(zipFs.getPath("/client.tiny")), "official", "named"
                ))
                .build();
            try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(destPath).build()) {
                consumer.addNonClassFiles(unmappedClientPath);
                remapper.readInputs(unmappedClientPath);
                remapper.apply(consumer);
            } finally {
                remapper.finish();
            }
        }
    }

    public static void generateOneSix(Path root, Path gameJar) throws IOException {
        final Path patchesDir = root.resolve("patches");
        final Path librariesDir = root.resolve("libraries");
        Files.createDirectories(patchesDir);
        Files.createDirectories(librariesDir);
        copyResource("/onesix/instance.cfg", root.resolve("instance.cfg"));
        copyResource("/onesix/mmc-pack.json", root.resolve("mmc-pack.json"));
        copyResource("/onesix/customjar.json", patchesDir.resolve("customjar.json"));
        copyResource("/onesix/net.minecraft.json", patchesDir.resolve("net.minecraft.json"));
        Files.copy(gameJar, librariesDir.resolve("prcraft-1.jar"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyResource(String path, Path dest) throws IOException {
        try (InputStream is = PrcraftInstaller.class.getResourceAsStream(path)) {
            assert is != null;
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Building");
        final Path destFile = Paths.get(args.length == 0 ? "prcraft.jar" : args[0]);
        final Path parent = destFile.getParent();
        if (parent != null) {
            Files.createDirectories(destFile.getParent());
        }
        Files.deleteIfExists(destFile);
        runInstaller(destFile);
        System.out.println("Build complete!");
        Path oneSixDestPath = null;
        if (!GraphicsEnvironment.isHeadless()) {
            final int result = JOptionPane.showConfirmDialog(
                null,
                "Build complete!\nSaved to " + destFile + "\nWould you like to generate a OneSix (MultiMC/Prism) pack?",
                "prcraft installer",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE
            );
            if (result == JOptionPane.YES_OPTION) {
                final JFileChooser fileChooser = new JFileChooser(System.getProperty("user.dir"));
                fileChooser.setAcceptAllFileFilterUsed(false);
                final FileFilter extensionFilter = new FileNameExtensionFilter("OneSix pack (*.zip)", "zip");
                fileChooser.addChoosableFileFilter(extensionFilter);
                fileChooser.setAcceptAllFileFilterUsed(true);
                fileChooser.showSaveDialog(null);
                final File chosen = fileChooser.getSelectedFile();
                if (chosen != null) {
                    String path = chosen.toString();
                    if (fileChooser.getFileFilter() == extensionFilter && path.indexOf('.') == -1) {
                        path += ".zip";
                    }
                    oneSixDestPath = Paths.get(path);
                }
            }
        } else if (args.length > 1) {
            oneSixDestPath = Paths.get(args[1]);
        }
        if (oneSixDestPath != null) {
            System.out.println("Generating OneSix pack");
            Files.createDirectories(oneSixDestPath.getParent());
            Files.deleteIfExists(oneSixDestPath);
            try (FileSystem zipFs = FileSystems.newFileSystem(
                URI.create("jar:" + oneSixDestPath.toUri()), Collections.singletonMap("create", "true"), null
            )) {
                generateOneSix(zipFs.getPath("/"), destFile);
            }
            System.out.println("Generated OneSix pack");
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(
                    null,
                    "Generated OneSix pack at " + oneSixDestPath + "\nYou should be able to drag and drop this into MultiMC or PrismLauncher.",
                    "prcraft installer",
                    JOptionPane.INFORMATION_MESSAGE
                );
            }
        }
    }
}