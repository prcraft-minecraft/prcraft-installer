package io.github.gaming32.prcraftinstaller;

import at.spardat.xma.xdelta.JarPatcher;
import io.github.prcraftmc.striplib.ClassStripper;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.SimplePathVisitor;
import org.apache.commons.io.input.SequenceReader;
import org.objectweb.asm.*;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class PrcraftInstaller {
    public static final String ARTIFACT_ROOT = "https://maven.jemnetworks.com/releases/io/github/gaming32/prcraft";

    public static void runInstaller(Path outputFile, String targetEnv) throws IOException {
        final Path unmappedClientPath = Files.createTempFile("prcraft", ".jar");
        try {
            downloadFile(unmappedClientPath, "https://launcher.mojang.com/v1/objects/4a2fac7504182a97dcbcd7560c6392d7c8139928/client.jar");
            final String latestVersion = getLatestVersion();
            final SeekableByteChannel patchChannel = new SeekableInMemoryByteChannel();
            downloadFile(
                Channels.newOutputStream(patchChannel),
                ARTIFACT_ROOT + '/' + latestVersion + "/prcraft-" + latestVersion + ".zip"
            );
            runInstaller(patchChannel, latestVersion, unmappedClientPath, outputFile, targetEnv);
        } finally {
            Files.deleteIfExists(unmappedClientPath);
        }
    }

    public static String getLatestVersion() throws IOException {
        try (InputStream is = new URL(ARTIFACT_ROOT + "/maven-metadata.xml").openStream()) {
            final Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
            return document.getElementsByTagName("latest").item(0).getTextContent();
        } catch (ParserConfigurationException | SAXException e) {
            throw new RuntimeException(e);
        }
    }

    public static void runInstaller(
        SeekableByteChannel patchChannel,
        String version,
        Path vanillaJar,
        Path outputFile,
        String targetEnv
    ) throws IOException {
        final Path mappingsZipPath = Files.createTempFile("prcraft", ".zip");
        final Path mappedClientPath = Files.createTempFile("prcraft", ".jar");
        Files.deleteIfExists(mappedClientPath);
        try {
            downloadFile(mappingsZipPath, "https://mcphackers.org/versions/1.2.5.zip");

            try (ZipFile patch = new ZipFile(patchChannel)) {
                try (InputStream extraMappings = patch.getInputStream(patch.getEntry("extramappings.tiny"))) {
                    remapClient(mappingsZipPath, vanillaJar, mappedClientPath, extraMappings);
                }

                try (
                    ZipFile input = new ZipFile(mappedClientPath);
                    ZipArchiveOutputStream output = new ZipArchiveOutputStream(outputFile);
                    BufferedReader patchList = new BufferedReader(new InputStreamReader(patch.getInputStream(patch.getEntry("META-INF/file.list"))))
                ) {
                    output.setUseZip64(Zip64Mode.Never);

                    output.putArchiveEntry(new ZipArchiveEntry("version.txt"));
                    output.write(version.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    output.closeArchiveEntry();

                    patchList.readLine();
                    patchList.readLine();
                    new JarPatcher("prcraft-patch.zip", "1.2.5-mapped.jar")
                        .applyDelta(patch, input, output, patchList, "");
                }
            }
        } finally {
            Files.deleteIfExists(mappingsZipPath);
            Files.deleteIfExists(mappedClientPath);
        }
        if (targetEnv != null) {
            try (FileSystem fs = FileSystems.newFileSystem(outputFile, null)) {
                envStrip(fs.getRootDirectories().iterator().next(), targetEnv);
            }
        }
    }

    private static void envStrip(Path rootPath, String targetEnv) throws IOException {
        final Set<Path> exclusions = Files.readAllLines(rootPath.resolve("strip/" + targetEnv + ".txt"))
            .stream()
            .map(rootPath::resolve)
            .collect(Collectors.toSet());
        Files.walkFileTree(rootPath, new SimplePathVisitor() {
            final Type clientAnnotation = Type.getObjectType("net/minecraft/modding/api/Side$Client");
            final Type serverAnnotation = Type.getObjectType("net/minecraft/modding/api/Side$DedicatedServer");
            final Type thisSideAnnotation = targetEnv.equals("client") ? clientAnnotation : serverAnnotation;
            final Type otherSideAnnotation = targetEnv.equals("client") ? serverAnnotation : clientAnnotation;
            final ClassStripper.Builder stripperFactory = ClassStripper.builder()
                .annotation("client", clientAnnotation, "stripLambdas")
                .annotation("dedicated_server", serverAnnotation, "stripLambdas");
            final Set<Path> dirsToClear = new HashSet<>();

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (exclusions.contains(file)) {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                final String filename = file.getFileName().toString();
                if (!filename.endsWith(".class") || filename.equals("package-info.class")) {
                    return FileVisitResult.CONTINUE;
                }
                if (dirsToClear.contains(file.getParent())) {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                final ClassReader reader = getReader(file);
                final ClassStripper stripper = stripperFactory.build(targetEnv);
                reader.accept(stripper, 0);
                if (stripper.stripNothing()) {
                    return FileVisitResult.CONTINUE;
                }
                if (stripper.stripEntireClass()) {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                while (stripper.needsLambdaStripping()) {
                    reader.accept(stripper.findLambdasToStrip(), 0);
                }
                final ClassWriter writer = new ClassWriter(0);
                reader.accept(stripper.getResult().visitor(writer), 0);
                Files.write(file, writer.toByteArray(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (exclusions.contains(dir)) {
                    PathUtils.deleteDirectory(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                final Path packageInfoPath = dir.resolve("package-info.class");
                if (!Files.isRegularFile(packageInfoPath)) {
                    return FileVisitResult.CONTINUE;
                }
                final Set<Type> annotations = new HashSet<>();
                getReader(packageInfoPath).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        annotations.add(Type.getType(descriptor));
                        return null;
                    }
                }, 0);
                if (annotations.contains(otherSideAnnotation) && !annotations.contains(thisSideAnnotation)) {
                    dirsToClear.add(dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                dirsToClear.remove(dir);
                return FileVisitResult.CONTINUE;
            }

            ClassReader getReader(Path path) throws IOException {
                try (InputStream is = Files.newInputStream(path)) {
                    return new ClassReader(is);
                }
            }
        });
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

    private static void remapClient(Path mappingsZipPath, Path unmappedClientPath, Path destPath, InputStream extraMappings) throws IOException {
        try (FileSystem zipFs = FileSystems.newFileSystem(mappingsZipPath, null)) {
            final BufferedReader extraMappingsReader = new BufferedReader(new InputStreamReader(extraMappings));
            extraMappingsReader.readLine();
            final TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(
                    new BufferedReader(new SequenceReader(
                        Files.newBufferedReader(zipFs.getPath("/client.tiny")), extraMappingsReader
                    )), "official", "named"
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
        try {
            System.out.println("Building");
            final List<String> argsList = new ArrayList<>(Arrays.asList(args));
            final String targetEnv = argsList.remove("--server") ? "dedicated_server" : null;
            final Path destFile = Paths.get(
                argsList.isEmpty()
                    ? (targetEnv != null ? "prcraft_" + targetEnv + ".jar" : "prcraft.jar")
                    : argsList.get(0)
            );
            final Path parent = destFile.getParent();
            if (parent != null) {
                Files.createDirectories(destFile.getParent());
            }
            Files.deleteIfExists(destFile);
            runInstaller(destFile, targetEnv);
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
        } catch (Exception e) {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(
                    null,
                    "Failed to install\n" + e,
                    "prcraft installer",
                    JOptionPane.ERROR_MESSAGE
                );
            }
            throw e;
        }
    }
}
