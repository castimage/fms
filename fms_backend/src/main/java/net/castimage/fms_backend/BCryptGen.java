package net.castimage.fms_backend;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class BCryptGen {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        String hash = encoder.encode("Fms@ca5T1ma93");
        System.out.println(hash);
    }
}
