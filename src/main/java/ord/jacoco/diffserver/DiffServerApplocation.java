package ord.jacoco.diffserver;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DiffServerApplocation {
    public static void main(String[] args) {
        SpringApplication.run(DiffServerApplocation.class,args);
    }
}
