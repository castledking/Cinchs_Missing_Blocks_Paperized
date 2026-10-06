package net.cinchtail.cinchsmissingblocks.cmb.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class YamlWriter {
    public static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter bw = Files.newBufferedWriter(path)) {
            bw.write(content);
        }
    }
}
