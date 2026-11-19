package com.shiftbalance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ShiftBalanceApplication {
    public static void main(String[] args) {
        if (System.getenv("SHIFTBALANCE_DESKTOP_ROOT") != null) {
            try {
                com.shiftbalance.desktop.DesktopRuntime.run();
            } catch (Exception error) {
                error.printStackTrace(System.err);
                System.exit(1);
            }
            return;
        }
        SpringApplication.run(ShiftBalanceApplication.class, args);
    }
}
