package com.example.files;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import com.example.files.api.CleanupMaintenanceController;
import com.example.files.config.TrustedHeaderIdentityConfiguration;

@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(excludeFilters = {
    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CleanupMaintenanceController.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@Import(TrustedHeaderIdentityConfiguration.class)
public class FileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileServiceApplication.class, args);
    }
}
