import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class TestRef {
    public static void main(String[] args) throws Exception {
        URL[] urls = {new java.io.File("e:/program files/auto-minecraft/pokemon/libs/baritone-unoptimized.jar").toURI().toURL()};
        URLClassLoader cl = new URLClassLoader(urls);
        System.out.println("Checking Settings...");
        Class<?> clazz = cl.loadClass("baritone.api.Settings");
        for (Field f : clazz.getFields()) {
            if (f.getName().equals("chatControlAnyway")) {
                System.out.println("Found chatControlAnyway");
            }
        }
    }
}
