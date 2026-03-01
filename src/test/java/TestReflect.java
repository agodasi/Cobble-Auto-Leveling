import com.cobblemon.mod.common.battles.BagItemActionResponse;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

public class TestReflect {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = BagItemActionResponse.class;
        System.out.println("Constructors:");
        for (Constructor<?> c : clazz.getConstructors()) {
            System.out.println(c.toString());
            for (Parameter p : c.getParameters()) {
                System.out.println("  Param: " + p.getType() + " " + p.getName());
            }
        }
    }
}
