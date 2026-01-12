package me.eigenraven.mcfsck;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class App {

    private static final int SECTOR_COUNT = 32 * 32;

    /**
     * @return If changes were made
     */
    public static boolean fixMcr(final String filename, final ByteBuffer region) {
        if (region.remaining() < 8192) {
            System.out.println(filename + " was too short to be a valid region file");
            return false;
        }
        region.order(ByteOrder.BIG_ENDIAN);
        boolean dirty = false;
        for (int sector = 0; sector < SECTOR_COUNT; sector++) {
            final int locationOffset = sector * 4;
            final int locationVal = region.getInt(locationOffset);
            final int chunkX = ((sector % 32) * 16);
            final int chunkZ = ((sector / 32) * 16);
            final int chunkOffset = ((locationVal >> 8) & 0xFF_FF_FF) * 4096;
            final int chunkLength = (locationVal & 0xFF) * 4096;
            if (chunkLength == 0) {
                continue;
            }
            if (chunkOffset + chunkLength > region.remaining()) {
                System.out.println("Erasing chunk with sectors outside of file bounds at "
                        + filename
                        + " , chunkX="
                        + chunkX
                        + ", chunkZ="
                        + chunkZ);
                dirty = true;
                region.putInt(locationOffset, 0);
            }
            final int dataLength = region.getInt(chunkOffset);
            if (chunkOffset + 5 + dataLength > region.remaining()) {
                System.out.println("Erasing chunk with data outside of file bounds at "
                        + filename
                        + " , chunkX="
                        + chunkX
                        + ", chunkZ="
                        + chunkZ);
                dirty = true;
                region.putInt(locationOffset, 0);
            }
            final int compressionType = region.get(chunkOffset + 4);
            try {
                final ByteBuffer compressedData = region.slice(chunkOffset + 5, dataLength);
                final ByteArrayInputStream compressedInput = new ByteArrayInputStream(
                        compressedData.array(),
                        compressedData.arrayOffset(),
                        compressedData.remaining());
                final InputStream decompressedInput = switch (compressionType) {
                    case 1 -> new GZIPInputStream(compressedInput);
                    case 2 -> new InflaterInputStream(compressedInput);
                    default -> null;
                };
                if (decompressedInput == null) {
                    continue;
                }
                final BufferedInputStream bis = new BufferedInputStream(decompressedInput);
                bis.readAllBytes();
                bis.close();
                decompressedInput.close();
            } catch (IOException e) {
                System.out.println("Erasing chunk with failing decompression at "
                        + filename
                        + " , chunkX="
                        + chunkX
                        + ", chunkZ="
                        + chunkZ
                        + ", error = "
                        + e.getMessage());
                dirty = true;
                region.putInt(locationOffset, 0);
            }
        }
        return dirty;
    }

    public static void repairMcrs(Path levelDatLocation) throws IOException {
        final Path worldDir = levelDatLocation.getParent();
        try (final var stream = Files.walk(worldDir, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".mca")).forEach(p -> {
                try {
                    final Path relativePath = worldDir.relativize(p);
                    System.out.println("Processing " + relativePath);
                    final byte[] contents = Files.readAllBytes(p);
                    final ByteBuffer buffer = ByteBuffer.wrap(contents);
                    final boolean changed = fixMcr(relativePath.toString(), buffer);
                    if (changed) {
                        Files.move(
                                p,
                                p.resolveSibling(p.getFileName() + ".backup"),
                                StandardCopyOption.REPLACE_EXISTING);
                        Files.write(p, contents);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                JFileChooser chooser = new JFileChooser();
                FileFilter filter = new FileFilter() {

                    @Override
                    public boolean accept(File f) {
                        return !f.isFile() || f.getName().equalsIgnoreCase("level.dat");
                    }

                    @Override
                    public String getDescription() {
                        return "level.dat files";
                    }
                };
                chooser.setFileFilter(filter);
                int returnVal = chooser.showOpenDialog(null);
                if (returnVal != JFileChooser.APPROVE_OPTION) {
                    System.out.println("Approval option was not chosen, exiting");
                    return;
                }
                final Path selection = chooser.getSelectedFile().toPath();
                repairMcrs(selection);
            } catch (Exception e) {
                // ignore, it's just a theme
            }
        });
    }
}
