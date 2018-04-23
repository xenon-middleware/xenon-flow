package nl.esciencecenter.computeservice.rest.service.tasks;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.HashMap;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.commonwl.cwl.OutputParameter;
import org.commonwl.cwl.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import nl.esciencecenter.computeservice.rest.model.Job;
import nl.esciencecenter.computeservice.rest.model.JobRepository;
import nl.esciencecenter.computeservice.rest.model.JobState;
import nl.esciencecenter.computeservice.rest.model.StatePreconditionException;
import nl.esciencecenter.computeservice.rest.model.WorkflowBinding;
import nl.esciencecenter.computeservice.rest.service.JobService;
import nl.esciencecenter.computeservice.rest.service.XenonService;
import nl.esciencecenter.computeservice.rest.service.staging.DirectoryStagingObject;
import nl.esciencecenter.computeservice.rest.service.staging.FileStagingObject;
import nl.esciencecenter.computeservice.rest.service.staging.StagingManifest;
import nl.esciencecenter.computeservice.rest.service.staging.XenonStager;
import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.adaptors.NotConnectedException;
import nl.esciencecenter.xenon.filesystems.Path;

public class CwlStageOutTask implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(CwlStageOutTask.class);
	
	private String jobId;
	private XenonService service;
	private JobRepository repository;
	private Integer exitcode;
	private JobService jobService;
	private XenonStager stager;

//	public CwlStageOutTask(String jobId, Integer exitcode, XenonService service) throws XenonException {
	public CwlStageOutTask(String jobId, Integer exitcode, XenonStager stager, XenonService service) throws XenonException {
		this.jobId = jobId;
		this.service = service;
		this.repository = service.getRepository();
		this.jobService = service.getJobService();
		this.stager = stager;
		this.exitcode = exitcode;
	}
	
	@Override
	public void run() {
		Logger jobLogger = LoggerFactory.getLogger("jobs."+jobId);
		Job job = repository.findOne(jobId);
		if (job.getInternalState().isFinal()) {
			// The job is in a final state so it's likely failed
			// or cancelled.
			return;
		}

		try {
			
			if (job.getInternalState() == JobState.FINISHED){
				job = jobService.setJobState(jobId, JobState.FINISHED, JobState.STAGING_OUT);
			} else if (job.getInternalState() != JobState.STAGING_OUT) {
				throw new StatePreconditionException("State is: " + job.getInternalState() + " but expected either FINISHED or STAGING_OUT");
			}
			
//			XenonStager stager = new XenonStager(jobService, repository, service.getTargetFileSystem(), service.getRemoteFileSystem(), service);
	        // Staging back output
	        StagingManifest manifest = new StagingManifest(jobId, new Path(job.getId() + "/"));
	        manifest.setBaseurl((String) job.getAdditionalInfo().get("baseurl"));
	        
	        Path remoteDirectory = job.getSandboxDirectory();
	        Path outPath = remoteDirectory.resolve("stdout.txt");
	        Path errPath = remoteDirectory.resolve("stderr.txt");
	        Path localErrPath = new Path(errPath.getFileNameAsString());
	        
	        InputStream stderr = service.getRemoteFileSystem().readFromFile(errPath);
			String errorContents = IOUtils.toString(stderr, "UTF-8");
			if (errorContents.isEmpty()) {
	    		//throw new IOException("Output path " + outPath + " was empty!");
	    		jobLogger.warn("Error path " + errPath + " was empty!");
	    	} else {
	    		jobLogger.info("Standard Error:" + errorContents);
	    		manifest.add(new FileStagingObject(errPath, localErrPath));
	    	}
			
			InputStream stdout = service.getRemoteFileSystem().readFromFile(outPath);
	    	String outputContents = IOUtils.toString(stdout, "UTF-8");
	    	
	    	if (outputContents.isEmpty()) {
	    		//throw new IOException("Output path " + outPath + " was empty!");
	    		jobLogger.warn("Output path " + outPath + " was empty!");
	    	} else {
		    	jobLogger.info("Raw output: " + outputContents);
	  
		        WorkflowBinding outputMap = null;
		        // TODO: Try to stage back files even if the exitcode
		        // is not 0.
		        if (exitcode != null && exitcode.intValue() == 0) {
		        	// Add output from cwl run
		        	// Read in the workflow to get the outputs
		        	// TODO: Take care of optional outputs
		        	outputMap = addOutputStaging(manifest, new Path(job.getWorkflow()), outputContents);
		        }
		        
		        if (outputMap != null) {
		        	jobLogger.info("Fixed output: " + outputMap.toIndentedString());
		        	jobService.setOutputBinding(jobId, outputMap);
		        }
	    	}

	    	int tries = 0;
			boolean success = false;
			while(!success && tries < 3) {
				try {
					success = stager.stageOut(manifest, exitcode);
					tries++;
				} catch (NotConnectedException e) {
					if (tries <=3 ) {
						logger.warn("Try: " + tries + ". Exception during stage out, forcing new filesystem for next attempt");
						stager.setFileSystems(service.getSourceFileSystem(), service.getRemoteFileSystem());
					} else {
						logger.error("Failed to submit after " + tries + " tries, giving up");
					}
					continue;
				}
			}
		} catch (StatePreconditionException | IOException | XenonException e){
			jobLogger.error("Error during stage out of " + job.getName() + "(" +job.getId() +")", e);
			logger.error("Error during stage out of " + job.getName() + "(" +job.getId() +")", e);
		}
	}

	private WorkflowBinding addOutputStaging(StagingManifest manifest, Path localWorkflow, String outputContents) throws JsonParseException, JsonMappingException, IOException, XenonException {
		Path workflowPath = service.getSourceFileSystem().getWorkingDirectory().resolve(localWorkflow);
		String extension = FilenameUtils.getExtension(workflowPath.getFileNameAsString());
		Workflow workflow = Workflow.fromInputStream(service.getSourceFileSystem().readFromFile(workflowPath.toAbsolutePath()), extension);
    	
    	// Read the cwltool stdout to determine where the files are.
		ObjectMapper mapper = new ObjectMapper(new JsonFactory());
    	WorkflowBinding outputMap = mapper.readValue(new StringReader(outputContents), WorkflowBinding.class);
    	
    	for (OutputParameter parameter : workflow.getOutputs()) {
    		if (parameter.getType().equals("File")) {
    			String paramId = null;
    			if (outputMap.containsKey(parameter.getId())) {
    				paramId = parameter.getId();
    			} else if (parameter.getId().startsWith("#")) {
    				paramId = parameter.getId().split("/")[1];
    			}
				// This should either work or throw an exception, we know about the problem (type erasure).
				@SuppressWarnings("unchecked")
				HashMap<String, Object> fileOutput = (HashMap<String, Object>) outputMap.get(paramId);

				Path remotePath = new Path((String) fileOutput.get("path")).toAbsolutePath();
				Path localPath = new Path(remotePath.getFileNameAsString());
				manifest.add(new FileStagingObject(remotePath, localPath));
			
				String fullPath = manifest.getTargetDirectory().resolve(localPath).toString();
				fileOutput.put("path", fullPath);
				
				if (service.getConfig().getTargetFilesystemConfig().isHosted()) {
					UriComponentsBuilder b = UriComponentsBuilder.fromUriString(manifest.getBaseurl());
					b.pathSegment(service.getConfig().getTargetFilesystemConfig().getBaseurl());
					b.pathSegment(fullPath);
					fileOutput.put("location", b.build().toString());
				} else {
					UriComponentsBuilder b = UriComponentsBuilder.fromUriString(service.getTargetFileSystem().getLocation().toString());
					b.pathSegment(fullPath);
					fileOutput.put("location", b.build().toString());
				}
    		} else if (parameter.getType().equals("Directory")) {
    			String paramId = null;
    			if (outputMap.containsKey(parameter.getId())) {
    				paramId = parameter.getId();
    			} else if (parameter.getId().startsWith("#")) {
    				paramId = parameter.getId().split("/")[1];
    			}
    			
    			// This should either work or throw an exception, we know about the problem (type erasure).
				@SuppressWarnings("unchecked")
    			HashMap<String, Object> dirOutput = (HashMap<String, Object>) outputMap.get(paramId);

				Path remotePath = new Path((String) dirOutput.get("path")).toAbsolutePath();
				Path localPath = new Path(remotePath.getFileNameAsString());
				manifest.add(new DirectoryStagingObject(remotePath, localPath));
			
				String fullPath = manifest.getTargetDirectory().resolve(localPath).toString();
				dirOutput.put("path", fullPath);
				
				UriComponentsBuilder b = UriComponentsBuilder.fromUriString(service.getSourceFileSystem().getLocation().toString());
				b.pathSegment(fullPath);
				dirOutput.put("location", b.build().toString());
    		}
    	}
    	
    	return outputMap;
	}
}
