import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

public class FindBaritoe {
    public static void main(String[] args) throws Exception {
        byte[] search = "baritoe".getBytes(StandardCharsets.UTF_8);
        String[] paths = {"e:\\program files\\auto-minecraft\\pokemon", "C:\\Users\\kuhar\\.gradle"};
        for (String p : paths) {
            Files.walk(Paths.get(p)).filter(Files::isRegularFile).forEach(file -> {
                try {
                    byte[] data = Files.readAllBytes(file);
                    for (int i = 0; i < data.length - search.length; i++) {
                        boolean match = true;
                        for (int j = 0; j < search.length; j++) {
                            if (data[i+j] != search[j]) { match = false; break; }
                        }
                        if (match) {
                            System.out.println("FOUND IN: " + file.toAbsolutePath());
                            break;
                        }
                    }
                } catch (Exception e) {}
            });
        }
    }
}
