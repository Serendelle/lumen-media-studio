package com.houqing.tools.video_compressor_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VideoCompressorBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(VideoCompressorBackendApplication.class, args);
	}

}
