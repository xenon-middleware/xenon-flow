package nl.esciencecenter.computeservice.rest;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.embedded.EmbeddedServletContainerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

import nl.esciencecenter.computeservice.config.ComputeServiceConfig;
import nl.esciencecenter.computeservice.config.TargetAdaptorConfig;
import nl.esciencecenter.computeservice.rest.model.JobRepository;
import nl.esciencecenter.computeservice.rest.service.JobService;
import nl.esciencecenter.computeservice.rest.service.XenonService;
import nl.esciencecenter.computeservice.rest.service.staging.XenonStager;
import nl.esciencecenter.computeservice.rest.service.tasks.XenonMonitoringTask;
import nl.esciencecenter.xenon.XenonException;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@Configuration
@EnableAutoConfiguration
@EnableSwagger2
@ComponentScan(basePackages = {"nl.esciencecenter.computeservice.rest*"})
@EntityScan(basePackages = { "nl.esciencecenter.computeservice.rest*", "nl.esciencecenter.computeservice.cwl.*" })
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = { "nl.esciencecenter.computeservice.rest*",
		"nl.esciencecenter.computeservice.cwl.*" })
public class Application extends WebMvcConfigurationSupport implements CommandLineRunner, ApplicationListener<EmbeddedServletContainerInitializedEvent> {
	private static final Logger logger = LoggerFactory.getLogger(Application.class);
	
	@Value("${server.address}")
	private String bindAdress;
	
	@Value("${xenon.config}")
	private String xenonConfigFile;
	
	@Autowired
	ThreadPoolTaskScheduler taskScheduler;
	
	@Autowired
	XenonStager sourceToRemoteStager;
	
	@Autowired
	XenonStager remoteToTargetStager;
	
	@Autowired
	XenonService xenonService;

	@Bean
	public static ThreadPoolTaskScheduler taskScheduler() {
		final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		return scheduler;
	}
	
	@Bean
	public static XenonStager sourceToRemoteStager(XenonService xenonService, JobRepository repository, JobService jobService) throws XenonException {
		return new XenonStager(jobService, repository, xenonService.getSourceFileSystem(), xenonService.getRemoteFileSystem(), xenonService);
	}
	
	@Bean
	public static XenonStager remoteToTargetStager(XenonService xenonService, JobRepository repository, JobService jobService) throws XenonException {
		return new XenonStager(jobService, repository, xenonService.getTargetFileSystem(), xenonService.getRemoteFileSystem(), xenonService);
	}
	
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		ComputeServiceConfig config;
		try {
			config = ComputeServiceConfig.loadFromFile(new File(xenonConfigFile));
			TargetAdaptorConfig targetConfig = config.getTargetFilesystemConfig();
			
			if (targetConfig.isHosted() && targetConfig.getAdaptor().equals("file")) {
				registry.addResourceHandler(targetConfig.getBaseurl() + "/**").addResourceLocations("file:" + targetConfig.getLocation());
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		registry.addResourceHandler("swagger-ui.html")
				.addResourceLocations("classpath:/META-INF/resources/");

		registry.addResourceHandler("/webjars/**")
		        .addResourceLocations("classpath:/META-INF/resources/webjars/");
		super.addResourceHandlers(registry);
	}
	
	@Override
	public void onApplicationEvent(EmbeddedServletContainerInitializedEvent event) {
		// Running, waiting and finished jobs should be automatically picked up by the
		// XenonWaitingTask
		taskScheduler.scheduleAtFixedRate(new XenonMonitoringTask(xenonService, sourceToRemoteStager, remoteToTargetStager), 3000);

    	int port = event.getEmbeddedServletContainer().getPort();
		logger.info("Server running at: http://" + bindAdress + ":" + port);
	}

	@Override
	public void run(String... arg0) throws Exception {
		if (arg0.length > 0 && arg0[0].equals("exitcode")) {
			throw new ExitException();
		}
	}

	public static void main(String[] args) throws Exception {
		new SpringApplication(Application.class).run(args);
	}

	class ExitException extends RuntimeException implements ExitCodeGenerator {
		private static final long serialVersionUID = 1L;

		@Override
		public int getExitCode() {
			return 10;
		}

	}
}
