package com.example.shortener;

import com.example.shortener.config.ShortenerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Entry point of the URL shortener service. */
@SpringBootApplication
@EnableConfigurationProperties(ShortenerProperties.class)
public class UrlShortenerApplication {

  public static void main(String[] args) {
    SpringApplication.run(UrlShortenerApplication.class, args);
  }
}
