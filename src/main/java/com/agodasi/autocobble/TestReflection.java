package com.agodasi.autocobble;

import com.cobblemon.mod.common.Cobblemon;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class TestReflection {
    public static void run() {
        try {
            System.out.println("Cobblemon methods:");
            for (Method m : Cobblemon.class.getDeclaredMethods()) {
                System.out.println(m.getName() + " -> " + m.getReturnType().getName());
            }
            System.out.println("Cobblemon fields:");
            for (Field f : Cobblemon.class.getDeclaredFields()) {
                System.out.println(f.getName() + " -> " + f.getType().getName());
            }

            System.out.println("Cobblemon properties:");
            Object instance = Cobblemon.INSTANCE;
            System.out.println("INSTANCE: " + instance);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
